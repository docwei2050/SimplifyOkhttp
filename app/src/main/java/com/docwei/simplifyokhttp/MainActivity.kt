package com.docwei.simplifyokhttp

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import okhttp3.*
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.*

class MainActivity : AppCompatActivity() {
    var client: OkHttpClient = OkHttpClient.Builder().eventListener(object : EventListener() {
       /* override fun callStart(call: Call) {
            Log.e("okhttp", "callStart ")
        }

        override fun callEnd(call: Call) {
            Log.e("okhttp", "callEnd")
        }

        override fun callFailed(call: Call, ioe: IOException) {
            Log.e("okhttp", "callFailed")
        }

        override fun proxySelectStart(call: Call, url: HttpUrl) {
            Log.e("okhttp", "proxySelectStart ")
        }

        override fun proxySelectEnd(call: Call, url: HttpUrl, proxies: List<Proxy>) {
            Log.e("okhttp", "proxySelectEnd")
        }

        override fun responseBodyStart(call: Call) {
            Log.e("okhttp", "responseBodyStart ")
        }

        override fun responseBodyEnd(call: Call, byteCount: Long) {
            Log.e("okhttp", "responseBodyEnd ")
        }

        override fun responseHeadersStart(call: Call) {
            Log.e("okhttp", "responseHeadersStart")
        }

        override fun responseHeadersEnd(call: Call, response: Response) {
            Log.e("okhttp", "responseHeadersEnd")
        }

        override fun connectionAcquired(call: Call, connection: Connection) {
            Log.e("okhttp", "connectionAcquired")
        }

        override fun connectionReleased(call: Call, connection: Connection) {
            Log.e("okhttp", "connectionReleased")
        }

        override fun secureConnectStart(call: Call) {
            Log.e("okhttp", "secureConnectStart")
        }

        override fun secureConnectEnd(call: Call, handshake: Handshake?) {
            Log.e("okhttp", "secureConnectEnd")
        }*/

    }).build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }

    private var executorServiceOrNull: ExecutorService? = null

    @get:Synchronized
    @get:JvmName("executorService")
    val executorService: ExecutorService
        get() {
            if (executorServiceOrNull == null) {
                executorServiceOrNull = ThreadPoolExecutor(0, Int.MAX_VALUE, 60, TimeUnit.SECONDS,
                    SynchronousQueue(), ThreadFactory { runnable ->
                        Thread(runnable, "okhttp").apply {
                            isDaemon = false
                        }
                    })
            }
            return executorServiceOrNull!!
        }

    fun clickSyn(view: View) {
        for(i in 1..100){
           /* executorService.execute(object : Runnable {
                override fun run() {
                    Log.e("okhttp", "thread.name--->" + Thread.currentThread().name)
                    Thread.sleep(5000)

                }
            })*/

        }
    }






    fun click(view: View) {
        val request: Request = Request.Builder()
            .url("https://www.baidu.com/")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("okhttp", e.message)
            }

            @Throws(IOException::class)
            override fun onResponse(
                call: Call,
                response: okhttp3.Response
            ) { //  Log.e("okhttp", "onResponse: " + response.body().toString());
                Log.e("okhttp", response.toString())
            }
        })
        //jdk 8的语法 try 主要是用来解决流close

        /* val request: Request = Request.Builder()
                   .url("https://www.baidu.com/")
                   .build()
               var response = client.newCall(request).execute();
               if (response.isSuccessful) {
                   Log.e("okhttp", response.toString())
               }*/

    }


}
