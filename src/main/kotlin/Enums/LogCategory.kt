package Enums

enum class LogCategory
{
    Boot, //Logs during the boot process
    Config, //Logs related to config files.
    Admin, //Logs related to admin actions.
    Security, //Logs related to security actions.
    Api, //Logs related to API actions.
    User, //Logs related to user actions.
    Repo, //Logs related to repo actions.
    System //Logs related to system actions and undefined behavior.
}


enum class LogLevel
{
    Debug,
    Log, //If selected no category prepending will be written.
    Warn,
    Error,
    Fatal
}