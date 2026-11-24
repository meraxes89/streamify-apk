package com.streamify.supabase

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException

/**
 * Ephemeral localhost HTTP loopback server for Desktop OAuth 2.1 authentication.
 *
 * Implements RFC 8252 Section 7.3 (Loopback Interface Redirection):
 * - Binds to dynamic port 0 on loopback IPv4 127.0.0.1.
 * - Serves a confirmation HTML response to the browser upon receiving the redirect.
 * - Automatically terminates after handling the callback or upon reaching the 120s timeout.
 */
class LoopbackAuthServer {

    private var serverSocket: ServerSocket? = null
    val port: Int
        get() = serverSocket?.localPort ?: 0

    /**
     * Initializes the server socket on an ephemeral free port.
     * @return The dynamically allocated port number.
     */
    fun start(): Int {
        val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        return socket.localPort
    }

    /**
     * Awaits the incoming OAuth redirect request from the system browser.
     * @param timeoutSeconds Max duration to wait before timing out (default 120s).
     */
    suspend fun awaitCallback(timeoutSeconds: Long = 120): Result<AuthCallbackData> = withContext(Dispatchers.IO) {
        val socket = serverSocket ?: return@withContext Result.failure(IllegalStateException("Server not started"))
        try {
            socket.soTimeout = (timeoutSeconds * 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val client = socket.accept()

            var callbackData = AuthCallbackData()
            client.use { clientSocket ->
                val reader = BufferedReader(InputStreamReader(clientSocket.getInputStream()))
                val writer = OutputStreamWriter(clientSocket.getOutputStream(), Charsets.UTF_8)

                val requestLine = reader.readLine()
                if (!requestLine.isNullOrBlank()) {
                    val parts = requestLine.split(" ")
                    if (parts.size >= 2) {
                        val pathAndQuery = parts[1]
                        val fullUri = "http://127.0.0.1:$port$pathAndQuery"
                        callbackData = AuthCallbackParser.parse(fullUri)
                    }
                }

                val htmlBody = if (callbackData.isSuccess) {
                    SUCCESS_HTML
                } else {
                    ERROR_HTML.replace("{{ERROR}}", callbackData.errorDescription ?: callbackData.error ?: "Unknown error")
                }

                val response = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: text/html; charset=UTF-8\r\n")
                    append("Content-Length: ${htmlBody.encodeToByteArray().size}\r\n")
                    append("Connection: close\r\n\r\n")
                    append(htmlBody)
                }

                writer.write(response)
                writer.flush()
            }

            Result.success(callbackData)
        } catch (e: SocketTimeoutException) {
            Result.failure(Exception("Authentication timed out. No response received from browser within $timeoutSeconds seconds."))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            stop()
        }
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
    }

    companion object {
        private const val SUCCESS_HTML = """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Streamify Authentication</title>
  <style>
    body {
      background-color: #060608;
      color: #FFFFFF;
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
      display: flex;
      align-items: center;
      justify-content: center;
      height: 100vh;
      margin: 0;
    }
    .card {
      background-color: #121216;
      border: 1px solid #23232A;
      border-radius: 16px;
      padding: 40px;
      text-align: center;
      max-width: 420px;
      box-shadow: 0 12px 40px rgba(0, 0, 0, 0.5);
    }
    .badge {
      display: inline-block;
      background-color: rgba(74, 222, 128, 0.15);
      color: #4ADE80;
      border: 1px solid rgba(74, 222, 128, 0.3);
      padding: 6px 16px;
      border-radius: 20px;
      font-size: 13px;
      font-weight: 600;
      margin-bottom: 20px;
    }
    h1 {
      font-size: 22px;
      font-weight: 600;
      margin: 0 0 12px 0;
      letter-spacing: -0.02em;
    }
    p {
      font-size: 14px;
      color: #A0A0A8;
      line-height: 1.5;
      margin: 0 0 24px 0;
    }
    .hint {
      font-size: 12px;
      color: #71717A;
    }
  </style>
</head>
<body>
  <div class="card">
    <div class="badge">Authenticated</div>
    <h1>Welcome to Streamify</h1>
    <p>Your authentication was successful. You can safely close this browser window and return to Streamify.</p>
    <div class="hint">This tab will close automatically.</div>
  </div>
  <script>
    setTimeout(function() { window.close(); }, 2500);
  </script>
</body>
</html>"""

        private const val ERROR_HTML = """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <title>Streamify Authentication Failed</title>
  <style>
    body {
      background-color: #060608;
      color: #FFFFFF;
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
      display: flex;
      align-items: center;
      justify-content: center;
      height: 100vh;
      margin: 0;
    }
    .card {
      background-color: #121216;
      border: 1px solid #3F1D1D;
      border-radius: 16px;
      padding: 40px;
      text-align: center;
      max-width: 420px;
    }
    .badge {
      display: inline-block;
      background-color: rgba(248, 113, 113, 0.15);
      color: #F87171;
      padding: 6px 16px;
      border-radius: 20px;
      font-size: 13px;
      font-weight: 600;
      margin-bottom: 20px;
    }
    h1 { font-size: 20px; margin: 0 0 12px 0; }
    p { font-size: 14px; color: #A0A0A8; line-height: 1.5; }
  </style>
</head>
<body>
  <div class="card">
    <div class="badge">Authentication Failed</div>
    <h1>Unable to Complete Sign-In</h1>
    <p>{{ERROR}}</p>
  </div>
</body>
</html>"""
    }
}
