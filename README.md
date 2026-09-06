# Dashtone #

[![Android CI](https://github.com/michaelkourlas/dashtone/actions/workflows/android.yml/badge.svg)](https://github.com/michaelkourlas/dashtone/actions/workflows/android.yml)

## Overview ##

Dashtone is a free and open-source local media player for Android Automotive.

[<img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png"
alt="Get it on Google Play"
height="50">](https://play.google.com/store/apps/details?id=net.kourlas.dashtone)

## Features ##

* Browse by album, artist, song, genre, album artist, composer, decade, or folder
* Support for M3U playlists
* Comprehensive shuffle support (shuffle songs, categories, or both)

## Rationale ##

Dashtone is designed to be used with a music library that is managed independently of the car, and kept up-to-date with a file synchronization tool like Syncthing.

As such, it intentionally does not support features that modify the library or that rely on car-specific state, including:
* Creating, modifying, or deleting playlists
* Favourites
* Tracking recently added or frequently played songs

## Installation ##

As an Android Automotive app, Dashtone must be downloaded and installed from the [Google Play Store](https://play.google.com/store/apps/details?id=net.kourlas.dashtone).

You can also build and install it yourself on your own vehicle if you have a Google Play developer account and use an internal testing track.

## Documentation ##

Dashtone's documentation is available in the [HELP.md file](HELP.md).

## License ##

Dashtone is licensed under the [Apache License 2.0](http://www.apache.org/licenses/LICENSE-2.0).

Google Play and the Google Play logo are trademarks of Google LLC.
