package com.example.plugins

import Enums.LogLevel
import Global.serverEnv
import KeyStore.keyStore
import Log.arkLog
import Structs.Api.LogResponse
import Structs.Api.Request
import Structs.LockedAuthKey
import Structs.PortableAuthKey
import Structs.StringPair
import Structs.UserSettings
import Tasks.Enums.TaskCategory
import Tasks.TaskRunner.runLockedKeyTask
import Util.decryptString
import Util.deserialize
import Util.encryptString
import Util.getClientKey
import Util.getServerKey
import Util.serialize
import io.ktor.client.statement.HttpResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureApiRoutes() {
    routing {
        route("/ark") {

            post("/request")
            {
                if (!validateAuthKey(call)) return@post //Confirms the auth key is a valid Ark auth key.
                var requestBody = call.receiveText() //Collect the encrypted request body.
                val encryptedAuthKey = getAuthKeyFromBearer(call) //Grab the auth key from the bearer.

                //Decrypt the auth key using the server's cached key. This assumes it was properly generated upon boot.
                val decryptedAuthKey = decryptString(encryptedAuthKey, serverEnv.get().getAuthSettings().cachedKey)

                //Test to see if this is a locked key. If so we'll proceed to run the next steps of potentially executing.
                val lockedKey = deserialize<LockedAuthKey>(decryptedAuthKey)
                if(lockedKey != null)
                {
                    //Next, we need to attempt to validate the locked key to confirm it's allowed.
                    val arkUser = keyStore.validateLockedKey(lockedKey)
                    if(arkUser != null)
                    {
                        /**
                         * To avoid massive bottlenecks with Argon2 we cache a user's key the first time they are valid
                         * and connected to the system. This allows us to avoid having to rehash the programmatic key
                         * the user is using to communicate to Ark and back.
                         */
                        var cachedKey = keyStore.findCachedUserEncryptionKey(lockedKey)
                        if(cachedKey == null)
                        {
                            //If the user doesn't have a cached key, we'll generate one and store it.
                            val newKey = getClientKey(lockedKey.userId, lockedKey.hwid)
                            keyStore.cacheUserEncryptionKey(lockedKey, newKey) //Cache the key for performance reasons.
                            cachedKey = keyStore.findCachedUserEncryptionKey(lockedKey)
                        }

                        //The key has been found so we can avoid caching and creating it through the argon hash again.
                        requestBody = decryptString(requestBody, cachedKey!!) //Assuring non-null because of above if statement.
                        val requestAsObject = deserialize<Request>(requestBody) //Attempt to transform back to a request.

                        //Ensure some kind of request was sent before proceeding and wasting our time.
                        if(requestAsObject == null)
                        {
                            val logResponse = LogResponse()
                            logResponse.message = "No request has been sent."
                            logResponse.error = true
                            val returnMessage = serialize(logResponse)
                            call.respond(returnMessage)
                            return@post //Exit because the user didn't actually send any request.
                        }

                        /**
                         * Execute the function that's embedded in the request and await the result as json.
                         * Then, encrypt it using the client's encryption key and return it.
                         */
                        if(requestAsObject.function != null)
                        {
                            /**
                             * Functions will likely need to do permissions checks. To avoid a mess of
                             * unpleasant if statements and return values exceptions will be thrown instead to
                             * help keep the code readable. This results in us needing to use a try catch
                             * block here to ensure we don't crash since the exception is intended to end the function.
                             */
                            try{
                                val apiResult = requestAsObject.function!!(requestAsObject.json, arkUser)
                                val encryptedResponse = encryptString(apiResult, cachedKey)
                                call.respond(encryptedResponse)
                                return@post
                            }
                            catch (e : Exception)
                            {
                                println(e)
                                call.respond(HttpStatusCode.Unauthorized)
                                return@post
                            }
                        }
                    }

                    else
                    {
                        /**
                         * Deliberately respond with nothing at all. This will confuse the ark client which sent
                         * a clearly invalid key. This is intended to slow down malicious actions like someone
                         * using ark-d or ark-cmd to spam requests to the server in an attempt
                         * to ddos it using a fake Ark key that was either revoked, or is a dummy.
                         */
                        call.respond("")
                        return@post
                    }
                }

                /**
                 * Not a locked key but is a valid ark key. Test if it's a portable key and if so, attempt to issue it.
                 * If the key returns null, the attempt to issue it will instantly exit and return an empty string.
                 * In any failure case, the ark client should know exactly how to handle an empty string response
                 * to a portable key issuance request. However, any other system will not know what to do with the empty
                 * response and will slow down attack attempts.
                 */
                else
                {
                    /**
                     * Extract request body by decrypting using the server's initial key. Since a client is attempting
                     * to use a portable key they are likely not in a state they are aware of their username yet.
                     * As such, they would not be able to use programmatic encryption key  at this stage so we
                     * would need to use the default initial key instead.
                     *
                     * In the client's request is a single value as the string. Which would be their hwid needed to
                     * confirm the key issuance. The lockedKeyTask expects this data to be in the form of a string pair.
                     * So we have to construct that object and serialize it before moving it into the task runner.
                     */
                    requestBody = decryptString(requestBody, serverEnv.get().getAuthSettings().cachedInitialKey)
                    val requestAsObject = deserialize<Request>(requestBody)
                    val hwid = requestAsObject?.json ?: ""
                    val stringPair = StringPair(encryptedAuthKey, hwid)
                    val lockedKeyTaskParamJson = serialize(stringPair)

                    /**
                     * Normally a task runner would be the function reference to execute as a rpc once we've validated
                     * the auth key. However, here we have to manually call it with a try catch block since a portable
                     * key detection is special, and doesn't require the client to send a rpc, only the key as the auth
                     * bearer, and the hwid as the request body. So instead, we have to run the try catch manually here
                     * to attempt to run the lockedKeyTask.
                     *
                     * @see Tasks.TaskManager.TaskObjects.LockedKeyTask.runTask
                     */
                    try{
                        val result = runLockedKeyTask(lockedKeyTaskParamJson, UserSettings())
                        val logResponse = LogResponse()
                        logResponse.message = result
                        logResponse.error = false
                        var responseJson = serialize(logResponse)
                        responseJson = encryptString(responseJson, serverEnv.get().getAuthSettings().cachedInitialKey)
                        call.respond(responseJson)
                    }
                    catch (e : Exception)
                    {
                        call.respond(HttpStatusCode.Unauthorized)
                    }

                    return@post
                }

                call.respond(HttpStatusCode.InternalServerError, "")
                return@post
            }


            /**
             * Ark supports only one get call that performs a task which is to unlock a locked key, and return the username to the
             * client. The client can then use this to generate their programmatic key for any api call authorization.
             */
            get("/connect") {
                if (!validateAuthKey(call)) return@get
                val encryptedAuthKey = getAuthKeyFromBearer(call)
                val decryptedAuthKey = decryptString(encryptedAuthKey, serverEnv.get().getAuthSettings().cachedKey)
                val lockedKey = deserialize<LockedAuthKey>(decryptedAuthKey)
                val username = lockedKey?.userId ?: ""
                val logResponse = LogResponse()
                logResponse.message = username
                logResponse.error = false
                var responseJson = serialize(logResponse)
                responseJson = encryptString(responseJson, serverEnv.get().getAuthSettings().cachedInitialKey)


                if(username.isEmpty())
                {
                    arkLog(TaskCategory.Security, "Invalid auth key supplied at connect endpoint.", LogLevel.Warn)
                    call.respond(HttpStatusCode.Unauthorized)
                    return@get
                }

                call.respond(responseJson)
                return@get
            }


            /**
             * All for status pings if the Ark security settings enable it. Ark will respond to
             */
            get("/status") {
                if(serverEnv.get().getGlobalSettings().allowStatusPing)
                {
                    var response = "Ark Version Control System"
                    response = encryptString(response, serverEnv.get().getAuthSettings().cachedInitialKey)
                    call.respond(response)
                    return@get
                }

                call.respond(HttpStatusCode.Unauthorized)
            }
        }
    }
}



suspend fun validateAuthKey(call: ApplicationCall): Boolean {
    val authHeader = call.request.headers["Authorization"]
    if (authHeader?.startsWith("Bearer ") != true) {
        call.respond(HttpStatusCode.Unauthorized, "Missing or invalid authorization header")
        return false
    }

    //Get Ark auth key from the string. Can be either locked or portable.
    val encryptedToken = authHeader.substring(7)

    /**
     * We're expecting that the server has already correctly setup, and cached it's key. Because each api call
     * will require a key check and the server decrypting the key we don't want to be slowed down by the
     * expensive argon hashing step for every single call.
     */
    val serverKey = serverEnv.get().getAuthSettings().cachedKey
    val decryptedJson = decryptString(encryptedToken, serverKey)
    
    if (decryptedJson.isEmpty()) {
        call.respond(HttpStatusCode.Unauthorized, "")
        return false
    }
    
    // Try LockedAuthKey first
    val lockedKey = deserialize<LockedAuthKey>(decryptedJson)
    if (lockedKey != null && lockedKey.userId.isNotEmpty() && lockedKey.hwid.isNotEmpty()) {
        return true
    }
    
    // Try PortableAuthKey
    val portableKey = deserialize<PortableAuthKey>(decryptedJson)
    if (portableKey != null && portableKey.isValid()) {
        return true
    }
    
    call.respond(HttpStatusCode.Unauthorized, "")
    return false
}


fun getAuthKeyFromBearer(call: ApplicationCall) : String
{
    val authHeader = call.request.headers["Authorization"]
    if (authHeader?.startsWith("Bearer ") != true) {
        return ""
    }

    //Get Ark auth key from the string. Can be either locked or portable.
    val encryptedToken = authHeader.substring(7)
    return encryptedToken
}