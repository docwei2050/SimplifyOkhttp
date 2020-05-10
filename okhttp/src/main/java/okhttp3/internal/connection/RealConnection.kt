/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package okhttp3.internal.connection

import android.util.Log
import okhttp3.*
import okhttp3.Handshake.Companion.handshake
import okhttp3.internal.assertThreadDoesntHoldLock
import okhttp3.internal.closeQuietly
import okhttp3.internal.concurrent.TaskRunner
import okhttp3.internal.http.ExchangeCodec
import okhttp3.internal.http1.Http1ExchangeCodec
import okhttp3.internal.http2.*
import okhttp3.internal.platform.Platform
import okhttp3.internal.tls.OkHostnameVerifier
import okio.BufferedSink
import okio.BufferedSource
import okio.buffer
import okio.sink
import okio.source
import java.io.IOException
import java.lang.ref.Reference
import java.net.*
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit.MILLISECONDS
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket

class RealConnection(
    val connectionPool: RealConnectionPool,
    private val route: Route
) : Http2Connection.Listener(), Connection {

    // The fields below are initialized by connect() and never reassigned.

    /** The low-level TCP socket. */
    private var rawSocket: Socket? = null

    /**
     * The application layer socket. Either an [SSLSocket] layered over [rawSocket], or [rawSocket]
     * itself if this connection does not use SSL.
     */
    private var socket: Socket? = null
    private var handshake: Handshake? = null
    private var protocol: Protocol? = null
    private var http2Connection: Http2Connection? = null
    private var source: BufferedSource? = null
    private var sink: BufferedSink? = null

    // The fields below track connection state and are guarded by connectionPool.

    /**
     * If true, no new exchanges can be created on this connection. Once true this is always true.
     * Guarded by [connectionPool].
     */
    //只要它是true这个connection就不能承载其他的流
    var noNewExchanges = false

    /**
     * The number of times there was a problem establishing a stream that could be due to route
     * chosen. Guarded by [connectionPool].
     */
    internal var routeFailureCount = 0

    internal var successCount = 0
    private var refusedStreamCount = 0

    /**
     * The maximum number of concurrent streams that can be carried by this connection. If
     * `allocations.size() < allocationLimit` then new streams can be created on this connection.
     */
    private var allocationLimit = 1

    /** Current calls carried by this connection. */
    val transmitters = mutableListOf<Reference<Transmitter>>()

    /** Nanotime timestamp when `allocations.size()` reached zero. */
    internal var idleAtNanos = Long.MAX_VALUE

    /**
     * Returns true if this is an HTTP/2 connection. Such connections can be used in multiple HTTP
     * requests simultaneously.
     * //http2是多路复用
     */
    val isMultiplexed: Boolean
        get() = http2Connection != null

    /** Prevent further exchanges from being created on this connection. */
    fun noNewExchanges() {
        connectionPool.assertThreadDoesntHoldLock()

        synchronized(connectionPool) {
            noNewExchanges = true
        }
    }

    fun connect(
        connectTimeout: Int,
        readTimeout: Int,
        writeTimeout: Int,
        pingIntervalMillis: Int,
        connectionRetryEnabled: Boolean,
        call: Call,
        eventListener: EventListener
    ) {
        check(protocol == null) { "already connected" }

        var routeException: RouteException? = null
        val connectionSpecs = route.address.connectionSpecs
        val connectionSpecSelector = ConnectionSpecSelector(connectionSpecs)
        //删除http明文传输的逻辑

        while (true) {
            try {
                connectSocket(connectTimeout, readTimeout, call, eventListener)
                establishProtocol(connectionSpecSelector, pingIntervalMillis, call, eventListener)
                eventListener.connectEnd(call, route.socketAddress, protocol)
                break
            } catch (e: IOException) {
                socket?.closeQuietly()
                rawSocket?.closeQuietly()
                socket = null
                rawSocket = null
                source = null
                sink = null
                handshake = null
                protocol = null
                http2Connection = null
                allocationLimit = 1

                eventListener.connectFailed(call, route.socketAddress, null, e)

                if (routeException == null) {
                    routeException = RouteException(e)
                } else {
                    routeException.addConnectException(e)
                }

                if (!connectionRetryEnabled || !connectionSpecSelector.connectionFailed(e)) {
                    throw routeException
                }
            }
        }

        if (route.requiresTunnel() && rawSocket == null) {
            throw RouteException(
                ProtocolException(
                    "Too many tunnel connections attempted: $MAX_TUNNEL_ATTEMPTS"
                )
            )
        }
    }

    /** Does all the work necessary to build a full HTTP or HTTPS connection on a raw socket. */
    @Throws(IOException::class)
    private fun connectSocket(
        connectTimeout: Int,
        readTimeout: Int,
        call: Call,
        eventListener: EventListener
    ) {
        val address = route.address

        val rawSocket = address.socketFactory.createSocket()!!
        this.rawSocket = rawSocket

        eventListener.connectStart(call, route.socketAddress)
        rawSocket.soTimeout = readTimeout
        try {
            Platform.get().connectSocket(rawSocket, route.socketAddress, connectTimeout)
        } catch (e: ConnectException) {
            throw ConnectException("Failed to connect to ${route.socketAddress}").apply {
                initCause(e)
            }
        }

        // The following try/catch block is a pseudo hacky way to get around a crash on Android 7.0
        // More details:
        // https://github.com/square/okhttp/issues/3245
        // https://android-review.googlesource.com/#/c/271775/
        //创建客户端的socket之后，直接创建读写io准备读写
        try {
            source = rawSocket.source().buffer()
            sink = rawSocket.sink().buffer()
        } catch (npe: NullPointerException) {
            if (npe.message == NPE_THROW_WITH_NULL) {
                throw IOException(npe)
            }
        }
    }

    @Throws(IOException::class)
    private fun establishProtocol(
        connectionSpecSelector: ConnectionSpecSelector,
        pingIntervalMillis: Int,
        call: Call,
        eventListener: EventListener
    ) {
        //创建建立tls握手
        eventListener.secureConnectStart(call)
        connectTls(connectionSpecSelector)
        eventListener.secureConnectEnd(call, handshake)
        //开始发送请求
        if (protocol === Protocol.HTTP_2) {
          //tls建立之后就开始http
            startHttp2(pingIntervalMillis)
        }
    }

    @Throws(IOException::class)
    private fun startHttp2(pingIntervalMillis: Int) {
        val socket = this.socket!!
        val source = this.source!!
        val sink = this.sink!!
        socket.soTimeout = 0 // HTTP/2 connection timeouts are set per-stream.
        val http2Connection =
            Http2Connection.Builder(client = true, taskRunner = TaskRunner.INSTANCE)
                .socket(socket, route.address.url.host, source, sink)
                .listener(this)
                .pingIntervalMillis(pingIntervalMillis)
                .build()
        this.http2Connection = http2Connection
        this.allocationLimit = Http2Connection.DEFAULT_SETTINGS.getMaxConcurrentStreams()
        http2Connection.start()
    }

    @Throws(IOException::class)
    private fun connectTls(connectionSpecSelector: ConnectionSpecSelector) {
        val address = route.address
        val sslSocketFactory = address.sslSocketFactory
        var success = false
        var sslSocket: SSLSocket? = null
        try {
            // Create the wrapper over the connected socket.

            //sslSocket 就是包装了一层ssl
            sslSocket = sslSocketFactory!!.createSocket(
                rawSocket,
                address.url.host,
                address.url.port,
                true /* autoClose */
            ) as SSLSocket

            // Configure the socket's ciphers, TLS versions, and extensions.
            val connectionSpec = connectionSpecSelector.configureSecureSocket(sslSocket)
            if (connectionSpec.supportsTlsExtensions) {
                Platform.get().configureTlsExtensions(sslSocket, address.protocols)
            }

            // Force handshake. This can throw!
            //强制握手
            sslSocket.startHandshake()
            // block for session establishment
            val sslSocketSession = sslSocket.session
            val unverifiedHandshake = sslSocketSession.handshake()

            // Verify that the socket's certificates are acceptable for the target host.
            //当我们自定义hostnameVerifier时会这么写
            //在握手期间，如果 URL 的主机名和服务器的标识主机名不匹配

            /* val hnv: HostnameVerifier = object : HostnameVerifier() {
               fun verify(hostname: String, session: SSLSession?): Boolean { //示例
                 return if ("yourhostname" == hostname) {
                   true
                 } else {
                   val hv: HostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
                   hv.verify(hostname, session)
                 }
               }
             }*/


            if (!address.hostnameVerifier!!.verify(address.url.host, sslSocketSession)) {
                //证书没得。。。
                val peerCertificates = unverifiedHandshake.peerCertificates
                if (peerCertificates.isNotEmpty()) {
                    val cert = peerCertificates[0] as X509Certificate
                    throw SSLPeerUnverifiedException(
                        """
              |Hostname ${address.url.host} not verified
              |    DN: ${cert.subjectDN.name}
              |    subjectAltNames: ${OkHostnameVerifier.allSubjectAltNames(cert)}
              """.trimMargin()
                    )
                } else {
                    throw SSLPeerUnverifiedException(
                        "Hostname ${address.url.host} not verified (no certificates)"
                    )
                }
            }


            // Success! Save the handshake and the ALPN protocol
            // 没出现问题就算成功了啊.
            val maybeProtocol = if (connectionSpec.supportsTlsExtensions) {
                Platform.get().getSelectedProtocol(sslSocket)
            } else {
                null
            }
            socket = sslSocket
            source = sslSocket.source().buffer()
            sink = sslSocket.sink().buffer()
            protocol = if (maybeProtocol != null) Protocol.get(maybeProtocol) else Protocol.HTTP_1_1
            success = true
        } finally {
            if (sslSocket != null) {
                Platform.get().afterHandshake(sslSocket)
            }
            if (!success) {
                sslSocket?.closeQuietly()
            }
        }
    }


    /**
     * Returns true if this connection can carry a stream allocation to `address`. If non-null
     * `route` is the resolved route for a connection.
     */
    internal fun isEligible(address: Address, routes: List<Route>?): Boolean {
        // If this connection is not accepting new exchanges, we're done.
        //如果请求数量 大于 流数量限制
        if (transmitters.size >= allocationLimit || noNewExchanges) return false

        //比较需要host&&端口一致，，，，，，，，，，，，，，，，，
        // If the non-host fields of the address don't overlap, we're done.
        if (!this.route.address.equalsNonHost(address)) return false

        //host一致
        // If the host exactly matches, we're done: this connection can carry the address.
        if (address.url.host == this.route().address.url.host) {
            return true // This connection is a perfect match.
        }

        // At this point we don't have a hostname match. But we still be able to carry the request if
        // our connection coalescing requirements are met. See also:
        // https://hpbn.co/optimizing-application-delivery/#eliminate-domain-sharding
        // https://daniel.haxx.se/blog/2016/08/18/http2-connection-coalescing/

        //host不匹配就一定不行吗，，不一定
        // 1. This connection must be HTTP/2.
        if (http2Connection == null) return false

        // 2. The routes must share an IP address.
        if (routes == null || !routeMatchesAny(routes)) return false

        // 3. This connection's server certificate's must cover the new host.
        if (address.hostnameVerifier !== OkHostnameVerifier) return false
        if (!supportsUrl(address.url)) return false

        // 4. Certificate pinning must match the host.
        /* try {
           address.certificatePinner!!.check(address.url.host, handshake()!!.peerCertificates)
         } catch (_: SSLPeerUnverifiedException) {
           return false
         }*/

        return true // The caller's address can be carried by this connection.
    }

    /**
     * Returns true if this connection's route has the same address as any of [candidates]. This
     * requires us to have a DNS address for both hosts, which only happens after route planning. We
     * can't coalesce connections that use a proxy, since proxies don't tell us the origin server's IP
     * address.
     */
    private fun routeMatchesAny(candidates: List<Route>): Boolean {
        return candidates.any {
            Log.e("okttp", "route.socketAddress-->" + route.socketAddress + "-----" + it.socketAddress)
            route.socketAddress == it.socketAddress
        }
    }

    fun supportsUrl(url: HttpUrl): Boolean {
        val routeUrl = route.address.url

        if (url.port != routeUrl.port) {
            return false // Port mismatch.
        }

        if (url.host == routeUrl.host) {
            return true // Host match. The URL is supported.
        }
        // We have a host mismatch. But if the certificate matches, we're still good.
        return handshake != null && OkHostnameVerifier.verify(
            url.host,
            handshake!!.peerCertificates[0] as X509Certificate
        )

    }

    @Throws(SocketException::class)
    internal fun newCodec(client: OkHttpClient, chain: Interceptor.Chain): ExchangeCodec {
        val socket = this.socket!!
        val source = this.source!!
        val sink = this.sink!!
        val http2Connection = this.http2Connection

        return if (http2Connection != null) {
            Http2ExchangeCodec(client, this, chain, http2Connection)
        } else {
          //读超时是给socket设置的
            socket.soTimeout = chain.readTimeoutMillis()
            source.timeout().timeout(chain.readTimeoutMillis().toLong(), MILLISECONDS)
            sink.timeout().timeout(chain.writeTimeoutMillis().toLong(), MILLISECONDS)
            Http1ExchangeCodec(client, this, source, sink)
        }
    }


    override fun route(): Route = route

    fun cancel() {
        // Close the raw socket so we don't end up doing synchronous I/O.
        rawSocket?.closeQuietly()
    }

    override fun socket(): Socket = socket!!

    /** Returns true if this connection is ready to host new streams. */
    fun isHealthy(doExtensiveChecks: Boolean): Boolean {
        val socket = this.socket!!
        val source = this.source!!
        if (socket.isClosed || socket.isInputShutdown || socket.isOutputShutdown) {
            return false
        }

        val http2Connection = this.http2Connection
        if (http2Connection != null) {
            return http2Connection.isHealthy(System.nanoTime())
        }

        if (doExtensiveChecks) {
            try {
                val readTimeout = socket.soTimeout
                try {
                    socket.soTimeout = 1
                  //连接所具备的source不能被耗尽。
                    return !source.exhausted()
                } finally {
                    socket.soTimeout = readTimeout
                }
            } catch (_: SocketTimeoutException) {
                // Read timed out; socket is good.
            } catch (_: IOException) {
                return false // Couldn't read; socket is closed.
            }
        }

        return true
    }

    /** Refuse incoming streams. */
    @Throws(IOException::class)
    override fun onStream(stream: Http2Stream) {
        stream.close(ErrorCode.REFUSED_STREAM, null)
    }

    /** When settings are received, adjust the allocation limit. */
    override fun onSettings(connection: Http2Connection, settings: Settings) {
        synchronized(connectionPool) {
            allocationLimit = settings.getMaxConcurrentStreams()
        }
    }

    override fun handshake(): Handshake? = handshake

    /**
     * Track a failure using this connection. This may prevent both the connection and its route from
     * being used for future exchanges.
     */
    internal fun trackFailure(e: IOException?) {
        connectionPool.assertThreadDoesntHoldLock()
        synchronized(connectionPool) {
            if (e is StreamResetException) {
                when (e.errorCode) {
                    ErrorCode.REFUSED_STREAM -> {
                        // Retry REFUSED_STREAM errors once on the same connection.
                        refusedStreamCount++
                        if (refusedStreamCount > 1) {
                            noNewExchanges = true
                            routeFailureCount++
                        }
                    }

                    ErrorCode.CANCEL -> {
                        // Keep the connection for CANCEL errors.
                    }

                    else -> {
                        // Everything else wants a fresh connection.
                        noNewExchanges = true
                        routeFailureCount++
                    }
                }
            } else if (!isMultiplexed || e is ConnectionShutdownException) {
                noNewExchanges = true

                // If this route hasn't completed a call, avoid it for new connections.
                if (successCount == 0) {
                    if (e != null) {
                        connectionPool.connectFailed(route, e)
                    }
                    routeFailureCount++
                }
            }
            return@synchronized // Keep synchronized {} happy.
        }
    }

    override fun protocol(): Protocol = protocol!!

    override fun toString(): String {
        return "Connection{${route.address.url.host}:${route.address.url.port}," +
                " hostAddress=${route.socketAddress}" +
                " cipherSuite=${handshake?.cipherSuite ?: "none"}" +
                " protocol=$protocol}"
    }

    companion object {
        private const val NPE_THROW_WITH_NULL = "throw with null exception"
        private const val MAX_TUNNEL_ATTEMPTS = 21

    }
}
