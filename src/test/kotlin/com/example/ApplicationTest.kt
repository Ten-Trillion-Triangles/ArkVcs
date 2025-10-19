package com.example

import Structs.Api.VoidRequest
import com.example.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.*

class ApplicationTest {
    @Test
    fun testRoot() = testApplication {
        application {
            configureRouting()
        }
        client.get("/").apply {
            assertEquals(HttpStatusCode.OK, status)
            assertEquals("Hello World!", bodyAsText())
        }
    }

    @Test
    fun testJson()
    {
        fun someFunction()
        {

        }

        val funReference = ::someFunction
        val request = VoidRequest()
        request.function = funReference
        val someString = Util.serialize(request)
        println(someString)
    }
}
