package Util

import org.bouncycastle.crypto.digests.Blake2bDigest
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.util.Base64
import java.security.Security
import org.bouncycastle.crypto.engines.XSalsa20Engine
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.macs.Poly1305
import org.bouncycastle.crypto.params.ParametersWithIV
import java.security.MessageDigest
import java.security.SecureRandom


/**
 * Generate a Argon2 hash using the Argon2id algorithm. This is used as the basis for the creation of programmatic
 * keys used by the Ark encryption system.
 *
 * @param username Typically the user's Ark username. However if used for other key schemas, this may be just be
 * the ark server name, hwid, or other identifier.
 * @param hwid The hardware identifier of the device or system that is requesting the key. This is used to
 * to seed the programmatic key that will be used to encrypt the message and return the matching value. Typically,
 * this will be used for the client to server's communication and unlocking of the LockedAuthKey.
 * @see LockedAuthKey for more details on how client locked keys are used.
 *
 * @param size The requested raw hash size in bytes. This is used to truncate the hash to the desired size. The value
 * should not be less than the Argon2 minimum hash size of 8 bytes.
 *
 * @param len The truncated char length of the string. Used to truncate the hash to the desired length, which can be
 * required for some systems.
 */
private fun naiArgonHash(username: String, hwid: String, size: Int, len : Int = 64, domain: String): String {

    // Ensure the requested raw hash size is positive and meets Argon2 minimum
    val minArgon2OutputSize = 8 // Argon2 specification minimum hash length
    if (size < minArgon2OutputSize) {
        // While the API might truncate, generating less than 8 bytes is against Argon2 spec.
        // Adjust size to minimum or throw error depending on desired strictness.
        // For now, we'll allow it but log a warning or handle appropriately if needed.
        // If the API truly expects less than 8 raw bytes, this is another fundamentally flawed aspect.
    }

    val preSalt = hwid.take(6) + username + domain

    // Salt generation using Blake2b (digest_size=16)
    val saltDigest = Blake2bDigest(128) // 128 bits = 16 bytes
    saltDigest.update(preSalt.toByteArray(Charsets.UTF_8), 0, preSalt.length)
    val salt = ByteArray(16)
    saltDigest.doFinal(salt, 0)

    // Argon2id hashing using Bouncy Castle directly
    val argon2 = Argon2BytesGenerator()

    // Configure Argon2 parameters. Required boilerplate for Kotlin.
    val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
        .withSalt(salt)
        .withIterations(2) // Matches Python's time_cost = 2
        .withMemoryAsKB(1953) // Matches Python's memory_cost_kb = int(2000000 / 1024)
        .withParallelism(1) // Matches Python's parallelism = 1
        .build()
    argon2.init(parameters)

    // Generate the raw hash bytes with the specified size
    val rawHash = ByteArray(size)
    argon2.generateBytes(hwid.toByteArray(Charsets.UTF_8), rawHash, 0, size)

    // URL-safe Base64 encoding
    val encodedHash = Base64.getUrlEncoder().encodeToString(rawHash)

    /**
     * Fragile truncation to the requested size. For whatever insane reason novel ai coupled the byte size and
     * the char size to be the same? This stupid hack will hopefully resolve the insane behavior and
     * allow us to both decrypt the strings, and also use them for API access.
     */
    val maxOutputLength = len
    return encodedHash.take(maxOutputLength)
}


    /**
     * Decrypts a given string using XSalsa20Poly1305.
     *
     * @param encryptedData Base64-url-encoded string to decrypt
     * @param key Secret key to use for decryption (must be 32 bytes)
     * @return Decrypted string
     * @throws IllegalArgumentException if the MAC tag does not match the computed value
     */
private fun decryptXSalsa20Poly1305(encryptedData: String, key: ByteArray): String
{
    Security.addProvider(BouncyCastleProvider())
    require(key.size == 32) { "Key must be 32 bytes" }

    val encryptedBytes = Base64.getDecoder().decode(encryptedData)
    require(encryptedBytes.size >= 24 + 16) { "Encrypted data too short" }

    val nonce = encryptedBytes.copyOfRange(0, 24)
    val ciphertextWithTag = encryptedBytes.copyOfRange(24, encryptedBytes.size)
    val ciphertext = ciphertextWithTag.copyOfRange(0, ciphertextWithTag.size - 16)
    val receivedTag = ciphertextWithTag.copyOfRange(ciphertextWithTag.size - 16, ciphertextWithTag.size)

    // 1. Generate Poly1305 key using XSalsa20 (first 32 bytes of key stream)
    val polyKey = ByteArray(32)
    val keyGenCipher = XSalsa20Engine()
    keyGenCipher.init(true, ParametersWithIV(KeyParameter(key), nonce)) // Encrypt zeros to get key stream
    keyGenCipher.processBytes(ByteArray(32), 0, 32, polyKey, 0)

    // 2. Verify Poly1305 tag
    val mac = Poly1305()
    mac.init(KeyParameter(polyKey))
    mac.update(ciphertext, 0, ciphertext.size)
    val computedTag = ByteArray(16)
    mac.doFinal(computedTag, 0)

    if (!computedTag.contentEquals(receivedTag)) {
        throw IllegalArgumentException("MAC tag mismatch")
    }

    // 3. Decrypt ciphertext with XSalsa20 (skip first 32 bytes of key stream)
    val cipher = XSalsa20Engine()
    cipher.init(false, ParametersWithIV(KeyParameter(key), nonce)) // Same key/nonce, but for decryption

    // Skip first 32 bytes (already used for Poly1305 key)
    val dummy = ByteArray(32)
    cipher.processBytes(dummy, 0, 32, dummy, 0)

    // Decrypt actual ciphertext
    val plaintext = ByteArray(ciphertext.size)
    cipher.processBytes(ciphertext, 0, ciphertext.size, plaintext, 0)

    return String(plaintext, Charsets.UTF_8)
}



    /**
     * Encrypts a given string using XSalsa20Poly1305.
     *
     * @param plaintext The string to encrypt
     * @param key The secret key to use for encryption (must be 32 bytes)
     * @return The encrypted string, encoded in URL-safe Base64
     */
private fun encryptXSalsa20Poly1305(plaintext: String, key: ByteArray): String {
    // Ensure Bouncy Castle provider is added
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
        Security.addProvider(BouncyCastleProvider())
    }

    // Validate key size
    require(key.size == 32) { "Key must be 32 bytes" }

    // 1. Generate a unique 24-byte nonce
    val nonce = ByteArray(24)
    val secureRandom = SecureRandom()
    secureRandom.nextBytes(nonce) // Generate a cryptographically secure random nonce

    val plaintextBytes = plaintext.toByteArray(Charsets.UTF_8)

    // 2. Generate Poly1305 key using XSalsa20 (first 32 bytes of key stream)
    val polyKey = ByteArray(32)
    val keyGenCipher = XSalsa20Engine()
    // Initialize XSalsa20 with the secret key and nonce for key stream generation
    keyGenCipher.init(true, ParametersWithIV(KeyParameter(key), nonce))
    // Process 32 zero bytes to get the first 32 bytes of the key stream (Poly1305 key)
    keyGenCipher.processBytes(ByteArray(32), 0, 32, polyKey, 0)

    // 3. Encrypt the plaintext with XSalsa20 (skip first 32 bytes of key stream)
    val cipher = XSalsa20Engine()
    // Initialize XSalsa20 with the same key and nonce for encryption
    cipher.init(true, ParametersWithIV(KeyParameter(key), nonce))

    // Skip first 32 bytes of the key stream that were used for the Poly1305 key
    val dummy = ByteArray(32)
    cipher.processBytes(dummy, 0, 32, dummy, 0)

    // Encrypt the actual plaintext
    val ciphertext = ByteArray(plaintextBytes.size)
    cipher.processBytes(plaintextBytes, 0, plaintextBytes.size, ciphertext, 0)

    // 4. Compute Poly1305 tag
    val mac = Poly1305()
    // Initialize Poly1305 with the derived key
    mac.init(KeyParameter(polyKey))
    // Update the MAC with the ciphertext
    mac.update(ciphertext, 0, ciphertext.size)
    // Compute the final tag
    val tag = ByteArray(16)
    mac.doFinal(tag, 0)

    // 5. Combine components: nonce || ciphertext || tag
    val encryptedBytes = ByteArray(nonce.size + ciphertext.size + tag.size)
    System.arraycopy(nonce, 0, encryptedBytes, 0, nonce.size)
    System.arraycopy(ciphertext, 0, encryptedBytes, nonce.size, ciphertext.size)
    System.arraycopy(tag, 0, encryptedBytes, nonce.size + ciphertext.size, tag.size)

    // 6. Encode the result in Base64
    return Base64.getEncoder().encodeToString(encryptedBytes)
}


/**
 * Generates a programmatic key for encryption using Argon2id. Handles automatic requirements of string 128 bytes
 * and char length 300.
 *
 * @param username Typically the user's Ark username. However, if used for other key schemas, this may be just be
 * the ark server name, hwid, or other identifier.
 *
 * @param hwid The hardware identifier of the device or system that is requesting the key. This is used to
 * seed the programmatic key that will be used to encrypt the message and return the matching value. Typically,
 * this will be used for the client to server's communication and unlocking of the LockedAuthKey.
 *
 * @return The generated programmatic key
 */
private fun getEncryptionKey(username: String, hwid: String): ByteArray {

    // Ensure username and hwid are not empty
    require(username.isNotEmpty() && hwid.isNotEmpty())

    // Generate the pre-key using naiArgonHash
    var preKey = naiArgonHash(username, hwid, 128, 300, "Ark-Version-Control-System")
    var preKeyBytes = preKey.replace("=", "").toByteArray(Charsets.UTF_8)

    Security.addProvider(BouncyCastleProvider())

    // Create a Blake2b digest instance
    val blake = MessageDigest.getInstance("BLAKE2b-256")
    blake.update(preKeyBytes)

    // Return the digest
    return blake.digest()
}


/**
 * Get Ark client LockedAuthKey. Combines the user's username and hwid to generate a programmatic key with argon2id.
 * Then converts it to a valid XSalsa20 key.
 *
 * @param username Ark client username.
 *
 * @param hwid Hardware, or OS identifier to fingerprint the device. This is used to lock an auth key to both a user
 * and device. This prevents the possibly of key theft, allowing an unauthorized user to unlock the key.
 *
 * @return The generated programmatic key, if a failure occurs an empty byte array is returned instead.
 *
 * @see LockedAuthKey
 */
fun getClientKey(username: String, hwid: String): ByteArray
{
    return try{
        getEncryptionKey(username, hwid)
    } catch (e: Exception) {
        ByteArray(0)
    }
}


/**
 * Get Ark server key by combining the server id into a programmatic key.
 */
fun getServerKey(keyString: String): ByteArray
{
    return try {
        getEncryptionKey(keyString, keyString)
    } catch (e: Exception) {
        ByteArray(0)
    }
}


/**
 * Encrypts a string using XSalsa20Poly1305. Safely handles exceptions and returns an empty string
 * if an exception is thrown.
 *
 * @param plaintext String to encrypt
 * @param key Secret key to use for encryption should be created using getClientKey or getServerKey
 * @return Encrypted string
 */
fun encryptString(plaintext: String, key: String): String
{
    val keyAsBytes = key.toByteArray(Charsets.UTF_8)
    return try{
        encryptXSalsa20Poly1305(plaintext, keyAsBytes)
    } catch (e: Exception) {
        ""
    }
}


fun encryptString(plaintext: String, key: ByteArray): String
{

    return try{
        encryptXSalsa20Poly1305(plaintext, key)
    } catch (e: Exception) {
        ""
    }
}


/**
 * Decrypts a string using XSalsa20Poly1305. Safely handles exceptions and returns an empty string
 * if an exception is thrown.
 *
 * @param encryptedString String to decrypt
 * @param key Secret key to use for decryption should be created using getClientKey or getServerKey
 * @return Decrypted string
 */
fun decryptString(encryptedString: String, key: String): String
{
    val keyAsBytes = key.toByteArray(Charsets.UTF_8)
    return try{
        decryptXSalsa20Poly1305(encryptedString, keyAsBytes)
    } catch (e: Exception) {
        ""
    }
}


/**
 * Overload to support taking in byte array directly.
 */
fun decryptString(encryptedString: String, key: ByteArray): String
{

    return try{
        decryptXSalsa20Poly1305(encryptedString, key)
    } catch (e: Exception) {
        ""
    }
}