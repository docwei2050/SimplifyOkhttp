/*
 * Copyright (C) 2014 Square, Inc.
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

import android.util.Log
import okhttp3.internal.assertThreadDoesntHoldLock
import okhttp3.internal.closeQuietly
import okhttp3.internal.connection.ConnectInterceptor
import okhttp3.internal.connection.Transmitter
import okhttp3.internal.http.BridgeInterceptor
import okhttp3.internal.http.CallServerInterceptor
import okhttp3.internal.http.RealInterceptorChain
import okhttp3.internal.http.RetryAndFollowUpInterceptor
import okhttp3.internal.platform.Platform
import okhttp3.internal.platform.Platform.Companion.INFO
import okhttp3.internal.threadName
import okio.Timeout
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

internal class RealCall private constructor(
  val client: OkHttpClient,
  /** The application's original request unadulterated by redirects or auth headers. */
  val originalRequest: Request,
  val forWebSocket: Boolean
) : Call {
  /**
   * There is a cycle between the [Call] and [Transmitter] that makes this awkward.
   * This is set after immediately after creating the call instance.
   */
  private lateinit var transmitter: Transmitter

  // Guarded by this.
  var executed: Boolean = false

  @Synchronized override fun isExecuted(): Boolean = executed

  override fun isCanceled(): Boolean = transmitter.isCanceled

  override fun request(): Request = originalRequest

  override fun execute(): Response {
    synchronized(this) {
      check(!executed) { "Already Executed" }
      executed = true
    }
    transmitter.timeoutEnter()
    transmitter.callStart()
    try {
      client.dispatcher.executed(this)
      //同步直接就去调用这个？？？
      return getResponseWithInterceptorChain()
    } finally {
      client.dispatcher.finished(this)
    }
  }

  override fun enqueue(responseCallback: Callback) {
    synchronized(this) {
      check(!executed) { "Already Executed" }
      executed = true
    }
    //调用开始
    transmitter.callStart()
    //调度器去调度
    client.dispatcher.enqueue(AsyncCall(responseCallback))
  }

  override fun cancel() {
    transmitter.cancel()
  }

  override fun timeout(): Timeout = transmitter.timeout()

  @SuppressWarnings("CloneDoesntCallSuperClone") // We are a final type & this saves clearing state.
  override fun clone(): RealCall {
    return newRealCall(client, originalRequest, forWebSocket)
  }

  internal inner class AsyncCall(
    private val responseCallback: Callback
  ) : Runnable {

    @Volatile private var callsPerHost = AtomicInteger(0)

    fun callsPerHost(): AtomicInteger = callsPerHost
   //重用同一个host的话，需要采用原子操作的一些类
    fun reuseCallsPerHostFrom(other: AsyncCall) {
      this.callsPerHost = other.callsPerHost
    }

    fun host(): String = originalRequest.url.host

    fun request(): Request = originalRequest

    fun get(): RealCall = this@RealCall

    /**
     * Attempt to enqueue this async call on [executorService]. This will attempt to clean up
     * if the executor has been shut down by reporting the call as failed.
     */
    fun executeOn(executorService: ExecutorService) {
      client.dispatcher.assertThreadDoesntHoldLock()

      var success = false
      try {
        executorService.execute(this)
        success = true
      } catch (e: RejectedExecutionException) {
        val ioException = InterruptedIOException("executor rejected")
        ioException.initCause(e)
        //失败了就把本次连接  给丢弃
        transmitter.noMoreExchanges(ioException)
        responseCallback.onFailure(this@RealCall, ioException)
      } finally {
        if (!success) {
          client.dispatcher.finished(this) // This call is no longer running!
        }
      }
    }
   //真正执行请求的地方
    override fun run() {
      threadName("OkHttp ${redactedUrl()}") {
        Log.e("okhttp","ReadCall 线程池"+Thread.currentThread().name)
        var signalledCallback = false
        transmitter.timeoutEnter()
        try {
          val response = getResponseWithInterceptorChain()
          signalledCallback = true
          responseCallback.onResponse(this@RealCall, response)
        } catch (e: IOException) {
          if (signalledCallback) {
            // Do not signal the callback twice!
            Platform.get().log("Callback failure for ${toLoggableString()}", INFO, e)
          } else {
            responseCallback.onFailure(this@RealCall, e)
          }
        } catch (t: Throwable) {
          cancel()
          if (!signalledCallback) {
            val canceledException = IOException("canceled due to $t")
            canceledException.addSuppressed(t)
            responseCallback.onFailure(this@RealCall, canceledException)
          }
          throw t
        } finally {
          client.dispatcher.finished(this)
        }
      }
    }
  }

  /**
   * Returns a string that describes this call. Doesn't include a full URL as that might contain
   * sensitive information.
   */
  fun toLoggableString(): String {
    return ((if (isCanceled()) "canceled " else "") +
        (if (forWebSocket) "web socket" else "call") +
        " to " + redactedUrl())
  }

  fun redactedUrl(): String = originalRequest.url.redact()

  @Throws(IOException::class)
  fun getResponseWithInterceptorChain(): Response {
    // Build a full stack of interceptors.
    val interceptors = mutableListOf<Interceptor>()
    //客户端的拦截器
    interceptors += client.interceptors
    //重试拦截器
    interceptors += RetryAndFollowUpInterceptor(client)
    //桥接拦截器
    interceptors += BridgeInterceptor(client.cookieJar)
/*    //缓存拦截器
    interceptors += CacheInterceptor(client.cache)*/
    //连接拦截器
    interceptors += ConnectInterceptor
    if (!forWebSocket) {
      //用户定义的网络拦截器可以对他进行进度监听
      interceptors += client.networkInterceptors
    }

    interceptors += CallServerInterceptor(forWebSocket)

    val chain = RealInterceptorChain(interceptors, transmitter, null, 0, originalRequest, this,
        client.connectTimeoutMillis, client.readTimeoutMillis, client.writeTimeoutMillis)

    var calledNoMoreExchanges = false
    try {
      val response = chain.proceed(originalRequest)
      if (transmitter.isCanceled) {
        response.closeQuietly()
        throw IOException("Canceled")
      }
      return response
    } catch (e: IOException) {
      calledNoMoreExchanges = true
      throw transmitter.noMoreExchanges(e) as Throwable
    } finally {
      if (!calledNoMoreExchanges) {
        transmitter.noMoreExchanges(null)
      }
    }
  }

  companion object {
    fun newRealCall(client: OkHttpClient, originalRequest: Request, forWebSocket: Boolean): RealCall {
      // Safely publish the Call instance to the EventListener.
      return RealCall(client, originalRequest, forWebSocket).apply {
        //给每一个call添加一个transmitter
        transmitter = Transmitter(client, this)
      }
    }
  }
}
