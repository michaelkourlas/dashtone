## Contents

* [Permissions](#permissions)
* [Library](#library)
* [Included folders](#included-folders)
* [Browse categories](#browse-categories)
* [Playlists](#playlists)
* [Shuffle](#shuffle)
* [Reporting bugs](#reporting-bugs)
* [Contacting the developer](#contacting-the-developer)

## Permissions

Dashtone needs the Music and audio permission to read your music library. If this permission hasn't been granted, browsing any category will show a "Grant Access" prompt in place of your library; tapping it opens the system permission dialog directly.

You can also check or change this at any time from the **Music Access** entry in Settings, which shows whether the permission is currently granted and lets you request it again. If you've previously denied the permission and told Android not to ask again, this entry instead takes you to the app's system Settings page, where you can grant it manually.

## Library

Dashtone doesn't manage your music files itself. It's designed to be used with a library that's kept up to date independently of the car, such as with a file synchronization tool like Syncthing.

Dashtone's own library is just a cache of whatever audio files Android's media store currently reports. Every scan -- whether it happens automatically in the background or you trigger it yourself -- fully re-syncs that cache with what's actually on the file system, including removing entries for anything that's no longer there.

Automatic scans happen in the background whenever Android reports that your media library has changed. You can also force an immediate rescan yourself using **Rescan Library** in Settings, such as right after adding new files.

## Included folders

By default, Dashtone scans every folder Android's media store reports. If a folder shouldn't be part of your library -- for example, a Podcasts folder living alongside Music on the same storage volume -- you can exclude it from **Included Folders** in Settings. Excluding a folder immediately triggers a rescan, so its songs disappear from your library right away.

## Browse categories

Dashtone can browse your library by Albums, Artists, Songs, Playlists, Genres, Album Artists, Composers, Decades, and Folders.

The car's tab strip only has room for a few categories at once, so only the first three categories you've enabled get their own tab; the rest are nested under a "More" tab. You can choose which categories are enabled and reorder them from **Browse Categories** in Settings -- this controls both which categories get their own tab and the order everything appears in, including within "More."

## Playlists

Dashtone supports M3U playlists. It doesn't let you create, modify, or delete playlists from within the app -- instead, playlist files are picked up automatically during a library scan and kept in sync with their contents. Adding, removing, or reordering tracks in the underlying `.m3u` file is reflected the next time your library is scanned.

## Shuffle

**Category Shuffle Mode** in Settings controls what the "Shuffle Albums," "Shuffle Artists," and similar actions throughout the browse tree do:
* **Shuffle categories**: albums, artists, etc. play in random order, but the songs within each play in their original order.
* **Shuffle songs**: albums, artists, etc. play in their original order, but the songs within each play in random order.
* **Shuffle categories and songs**: both play in random order.

## Reporting bugs

If you believe you've found a bug, feel free to file a report at the project's [GitHub repository](https://github.com/michaelkourlas/dashtone/issues).

## Contacting the developer

If you have any questions, feel free to contact me at [dashtone@kourlas.com](mailto:dashtone@kourlas.com).
