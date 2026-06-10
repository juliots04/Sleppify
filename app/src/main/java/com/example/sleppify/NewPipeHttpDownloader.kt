package com.example.sleppify

import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class NewPipeHttpDownloader private constructor() : Downloader() {

    override fun execute(request: Request): Response {
        val url = URL(request.url())
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = request.httpMethod()
        connection.connectTimeout = 12000
        connection.readTimeout = 15000
        connection.setRequestProperty("User-Agent", "Mozilla/5.0")

        for ((key, values) in request.headers()) {
            for (value in values) {
                connection.addRequestProperty(key, value)
            }
        }

        val dataToSend = request.dataToSend()
        if (dataToSend != null) {
            connection.doOutput = true
            connection.outputStream.use { it.write(dataToSend) }
        }

        val responseCode = connection.responseCode
        val responseMessage = connection.responseMessage ?: ""
        val responseHeaders = connection.headerFields
            ?.filterKeys { it != null }
            ?.mapValues { (_, v) -> v ?: emptyList() }
            ?: emptyMap()

        val body = try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (_: IOException) {
            connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
        }

        return Response(responseCode, responseMessage, responseHeaders, body, request.url())
    }

    companion object {
        private val INSTANCE = NewPipeHttpDownloader()

        @JvmStatic
        fun getInstance(): NewPipeHttpDownloader = INSTANCE
    }
}
