About Jonction
==============

Summary
-------

Jonction was initially written because I wanted a simple and reliable solution to download and synchronise a set of podcast feeds with my MP3 player

* For each feed I always want the n latest episodes on my player (meaning old episodes are automatically deleted)
* I do not want a GUI as I also want it to work for a headless server (I want to be able to use old headless PC as a podcast sync device)
* I do not want an external database for the files metadata. I want each downloaded file to hold its own metadata (meaning, I modify the ID3 tags of the files to normalize the information)
* I want the synchronisation to work both for MTP and mass-storage devices


Why a Scala script
------------------

Jonction happens to be a Scala script because when I decided that I wanted to code this tool, I was also in the process of learning Scala and I was curious to use Scala in script mode. Retrospectively, I didn't know that the script file would be that large and I should have setup a more traditional project structure with src/test. I will try to do that as soon as I have time because it is getting difficult to improve Jonction without a decent test suite.

The script also is for me a playfield to try Scala features !



