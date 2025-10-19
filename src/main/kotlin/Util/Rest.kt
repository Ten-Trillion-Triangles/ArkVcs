package Util

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.io.IOException

/**
 * Sends an HTTP GET request to the specified URL.
 *
 * @param url The URL to which the GET request is sent.
 * @param acceptType The value of the Accept header indicating the type of content that can be sent back.
 *                   Defaults to "any content".
 * @param authToken Optional authorization token to include in the request as a Bearer token.
 * @return The response body as a text string, or an error message in case of an IOException.
 */
suspend fun httpGet(url: String, acceptType: String = "*/*", authToken: String? = null): String
{
    val client = HttpClient()
    return try {
        val response: HttpResponse = client.get(url) {
            header(HttpHeaders.Accept, acceptType)
            authToken?.let {
                header(HttpHeaders.Authorization, "Bearer $it")
            }
        }
        response.bodyAsText()
    } catch (e: IOException) {
        "Error: ${e.message}"
    } finally {
        client.close()
    }
}



/**
 * Sends an HTTP PUT request to the specified URL.
 *
 * @param url The URL to which the PUT request is sent.
 * @param body The content of the request body.
 * @param acceptType The value of the Accept header indicating the type of content that can be sent back.
 *                   Defaults to "any content".
 * @param authToken Optional authorization token to include in the request as a Bearer token.
 * @return The response body as a text string, or an error message in case of an IOException.
 */
suspend fun httpPut(url: String, body: String, acceptType: String = "*/*", authToken: String? = null): String
{

    val client = HttpClient()

    return try {
        val response: HttpResponse = client.put(url) {
            setBody(body) // Set the request body
            contentType(ContentType.Application.Json) // Set content type, adjust if needed
            header(HttpHeaders.Accept, acceptType)
            authToken?.let {
                header(HttpHeaders.Authorization, "Bearer $it")
            }
        }
        response.bodyAsText()
    } catch (e: IOException) {
        "Error: ${e.message}"
    } finally {
        client.close()
    }
}


/**
 * Sends an HTTP POST request to the specified URL.
 *
 * @param url The URL to which the POST request is sent.
 * @param body The content of the request body.
 * @param acceptType The value of the Accept header indicating the type of content that can be sent back.
 *                   Defaults to "any content".
 * @param authToken Optional authorization token to include in the request as a Bearer token.
 * @return The response body as a text string, or an error message in case of an IOException.
 */
suspend fun httpPost(url: String, body: String, acceptType: String = "*/*", authToken: String? = null): String
{
    val client = HttpClient()

    return try {
        val response: HttpResponse = client.post(url) {
            setBody(body) // Set the request body
            contentType(ContentType.Application.Json) // Set content type, adjust if needed
            header(HttpHeaders.Accept, acceptType)
            authToken?.let {
                header(HttpHeaders.Authorization, "Bearer $it")
            }
        }
        response.bodyAsText()
    } catch (e: IOException) {
        "Error: ${e.message}"
    } finally {
        client.close()
    }
}



/**
 * Sends an HTTP DELETE request to the specified URL.
 *
 * @param url The URL to which the DELETE request is sent.
 * @param body Optional content of the request body.
 * @param authToken Optional authorization token to include in the request as a Bearer token.
 * @return The response body as a text string, or an error message in case of an IOException.
 */
suspend fun httpDelete(url: String, body: String = "", authToken: String? = null): String
{
    val client = HttpClient()

    return try {
        val response: HttpResponse = if (body.isEmpty()) {
            client.delete(url) {
                authToken?.let {
                    header(HttpHeaders.Authorization, "Bearer $it")
                }
            }
        } else {
            client.delete(url) {
                setBody(body) // Set the request body
                contentType(ContentType.Application.Json) // Set content type, adjust if needed
                authToken?.let {
                    header(HttpHeaders.Authorization, "Bearer $it")
                }
            }
        }
        response.bodyAsText()
    } catch (e: IOException) {
        "Error: ${e.message}"
    } finally {
        client.close()
    }
}