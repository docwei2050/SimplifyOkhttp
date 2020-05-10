/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3

import okhttp3.internal.toImmutableList
import java.net.Proxy
import java.net.ProxySelector
import java.util.Objects
import javax.net.SocketFactory
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSocketFactory

/**
 * A specification for a connection to an origin server. For simple connections, this is the
 * server's hostname and port. If an explicit proxy is requested (or [no proxy][Proxy.NO_PROXY] is explicitly requested),
 * this also includes that proxy information. For secure connections the address also includes the SSL socket factory,
 * hostname verifier, and certificate pinner.
 *
 * HTTP requests that share the same [Address] may also share the same [Connection].
 */
class Address(
  uriHost: String,
  uriPort: Int,
  /** Returns the service that will be used to resolve IP addresses for hostnames. */
  @get:JvmName("dns") val dns: Dns,

  /** Returns the socket factory for new connections. */
  @get:JvmName("socketFactory") val socketFactory: SocketFactory,

  /** Returns the SSL socket factory, or null if this is not an HTTPS address. */
  @get:JvmName("sslSocketFactory") val sslSocketFactory: SSLSocketFactory?,

  /** Returns the hostname verifier, or null if this is not an HTTPS address. */
  @get:JvmName("hostnameVerifier") val hostnameVerifier: HostnameVerifier?,

  protocols: List<Protocol>,
  connectionSpecs: List<ConnectionSpec>

) {
  /**
   * Returns a URL with the hostname and port of the origin server. The path, query, and fragment of
   * this URL are always empty, since they are not significant for planning a route.
   */
  @get:JvmName("url") val url: HttpUrl = HttpUrl.Builder()
      .scheme(if (sslSocketFactory != null) "https" else "http")
      .host(uriHost)
      .port(uriPort)
      .build()

  /**
   * The protocols the client supports. This method always returns a non-null list that
   * contains minimally [Protocol.HTTP_1_1].
   */
  @get:JvmName("protocols") val protocols: List<Protocol> = protocols.toImmutableList()

  @get:JvmName("connectionSpecs") val connectionSpecs: List<ConnectionSpec> =
      connectionSpecs.toImmutableList()

  override fun equals(other: Any?): Boolean {
    return other is Address &&
        url == other.url &&
        equalsNonHost(other)
  }

  override fun hashCode(): Int {
    var result = 17
    result = 31 * result + url.hashCode()
    result = 31 * result + dns.hashCode()

    result = 31 * result + protocols.hashCode()
    result = 31 * result + connectionSpecs.hashCode()
    result = 31 * result + Objects.hashCode(sslSocketFactory)
    result = 31 * result + Objects.hashCode(hostnameVerifier)
    return result
  }

  internal fun equalsNonHost(that: Address): Boolean {
    return this.dns == that.dns &&

        this.protocols == that.protocols &&
        this.connectionSpecs == that.connectionSpecs &&
        this.sslSocketFactory == that.sslSocketFactory &&
        this.hostnameVerifier == that.hostnameVerifier &&
        this.url.port == that.url.port
  }

  override fun toString(): String {
    return "Address{" +
        "${url.host}:${url.port}, "
  }
}
