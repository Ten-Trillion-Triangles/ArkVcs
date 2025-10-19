package Enums

import kotlinx.serialization.Serializable

/**
 * Denotes scope of a project's fork status.
 * Fork is a feature in ArkScm that merges branches with shelving.
 * The default setting is Repo.
 *
 * @param Repo Marked as the parent project and not a fork. Any project that's marked as Repo can be forked into
 * any other fork type.
 * @param HardFork Splits the project into a new unique project. It is no longer mergeable and cannot pull
 * from the original parent project. This setting is useful for versioning, and other situations where you want to
 * freeze a project at a point in time, or provide support for a specific release without requiring pulling future
 * changes from the project.
 * @param Mergeable Forks the given project in a mergeable fashion allowing for any changes made to it to be
 * merged back into the main project. Mergeable projects can be configured to allow pulling changes from the parent
 * or block pulling changes from the parent.
 * @param DownStream Forks the given project in a way that allows for pulling from the parent but does not allow
 * the fork to be merged back. Any changes made to a files in the fork marks that file as immutable and no longer can
 * pull changes for that file from the server. This is useful for platform specific changes that would not want
 * to be included in the main project.
 * @param Shelf Forks a project and all checked out files in a shelved way. This allows a changelist to be
 * set aside and reverted. If desired the changelist can be unshelved and pushed merged back into the parent.
 */
@Serializable
enum class ForkSettings
{
    Repo, //Is the parent project.
    HardFork, //Is hard forked from another project and no longer mergeable.
    Mergeable, //Is treated as a branch that can be merged back in.
    DownStream, //Project can make changes and pull from parent, but not merge into parent.
    Shelf //Is treated as a shelf which can be unshelved, checked out, and pushed to the repo.
}