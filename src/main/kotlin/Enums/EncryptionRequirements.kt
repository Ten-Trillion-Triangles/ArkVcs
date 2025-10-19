package Enums

//Denotes encryption and hashing requirements for auth keys and auth files.
enum class EncryptionRequirements
{
    AES, //Only use AES.
    bcrypt, //Also mandate bcrypt salting and hashing on top of AES.
    XSalsa //Default encryption method for Ark.
}