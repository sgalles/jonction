About Jonction
==============

Summary
-------

Jonction was initially written because I wanted a simple and reliable solution to download and synchronise a set of podcast feeds with my MP3 player

* For each feed I always want the n latest episodes on my player (meaning old episodes are automatically deleted). 
* I do not want a GUI as I also want it to work for a headless server (I want to be able to use old headless PC as a podcast sync device)
* I do not want an external database for the files metadata. I want each downloaded file to hold its own metadata (meaning, I may modify the ID3 tags of the files to normalize the available ID3 data)
* I want the synchronisation to work both for MTP and mass-storage devices
* I want to be able to modify the title of the episodes to improve the display of large titles on small screens
* I want the feed analysis to be fast, that why I wanted to use the Google Feed API as it has a cache of the feeds


Why a Scala script
------------------

Jonction happens to be a Scala script because when I decided that I wanted to code this tool, I was also in the process of learning Scala and I was curious to use Scala in script mode. Retrospectively, I didn't know that the script file would be that large and I should have setup a more traditional project structure with src/test. Some day, when I have time, I might retrofit the code script and create a real scala project because it is getting difficult to improve Jonction without a decent test suite.

Also, This script also is for me a playfield to try Scala features !


Current state
-------------

Currently, the script solve my initial use cases, I use it on a day to day basis with something like 20 URLs and an MTP player. No more "ho ! I should have downloaded some podcasts" when stuck in a traffic jam.

However, it really is an "alpha" quality script

* It is not tested on a large set of feeds
* Was tested only with my own MTP player
* The mass-storage MP3 player feature is not implemented yet

If you try Jonction on a MTP player that already contains important data, make sure you have a backup.

Prerequisite
============

* A Unix based system (was only tested on Linux, but may be easily adapted to work with cygwin)
* A Scala interpreter (I use Scala 2.7.3)
* a wget command line utility available in the PATH
* the mtp-tools set of MTP utilities available in the PATH
* The jaudiotagger.jar jar file from project [Jaudiotagger](http://www.jthink.net/jaudiotagger/)


Install
=======

* Copy the jaudiotagger.jar in the directory of the script
* Update the urls.txt file to add your own feeds (and get ride of mine, of course)
* Create the ~/Music/Jonction directory (or change the download.dir value in jonction.properties file to target an other directory). The podcasts will be downloaded in this directory before the upload to the device.

Run
===

* Connect your MP3 player (For now, only an MTP MP3 player)
* In the directory of the script run the ./jonction.sh launcher.




