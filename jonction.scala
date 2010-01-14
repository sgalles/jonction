import scala.io.Source
import java.io._
import java.net._
import java.util.Date
import java.util.Properties
import java.util.ArrayList
import java.text.SimpleDateFormat
import scala.xml._
import java.util.logging._
import java.lang.Thread


// Misc utils methods and singleton
def initJaudiotagger(){
	val prop = new Properties()
	prop.setProperty("org.jaudiotagger.level","OFF")
	val outStream = new ByteArrayOutputStream()
	prop.store(outStream,"")
	LogManager.getLogManager.readConfiguration(new ByteArrayInputStream(outStream.toByteArray))
}

def exec(cmds: List[String], workDir: File) : List[String]= {
 
		import scala.actors.Actor._

		println("command=" + cmds.mkString(" "))
                val pr = Runtime.getRuntime.exec(cmds.toArray,Array[String](), workDir);
		
		val errorReader = actor {
			val err = new BufferedReader(new InputStreamReader(pr.getErrorStream()));
			var error = err.read()
			while(error != -1){			
				error = err.read()
        		}		
		}
                
		val input = new BufferedReader(new InputStreamReader(pr.getInputStream()));
                var line = input.readLine()
		var lines = List[String]()
                while(line != null) {
			lines ::= line
			line = input.readLine()	
                }
		lines = lines.reverse
 
                val ret = pr.waitFor()
		if(ret != 0) throw new Exception("Command failed : '" + cmds.mkString(" ") + "'")
		lines
}

def retryWhile(continuePredicate: => Boolean)(command: => Unit) {
	for( i <- 1 to 100){
		command
		if(!continuePredicate) return
		println("Retrying " + i + "...")
	}
}

def Any2String(any: Any): String = any match { case s: String => s}
def Any2Date(any: Any): Date = any match { case s: String => DateParser.parse(s)}


object DateParser{
	val dateFormat = new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z");
	def parse(sDate: String) = dateFormat.parse(sDate)
	def format(date: Date) = dateFormat.format(date)
}

//Domain objects
class SimpleTrack(val title: String, val link: String){

	val ReFilename = """.+(?:/|%2F)(.+)""".r	
	
	def filename = link match { case ReFilename(f) => f}
	
}

class SimpleAlbum[+T <: SimpleTrack](val name: String, val tracks: List[T]){
	override def toString = "\nAlbum name: " + name + ( " " /: tracks)(_ + "\n" + _ + ",")
}

class Track(title: String, link: String, val pubDate: Date, val length: Int) extends SimpleTrack(title,link){

	override def toString = "Track(" + title + "," + link + "," + pubDate + "," + filename + "," + length + ")"
		
}

type Album = SimpleAlbum[Track]


type DeviceAlbum = SimpleAlbum[SimpleTrack]


// Repositories and services
class FeedReader(){

	import scala.util.parsing.json._

	def read(url: URL) = {
		val googleUrl = "http://ajax.googleapis.com/ajax/services/feed/load?q=" + url + "&v=1.0&output=json_xml"
		println(googleUrl)
		val connection = new URL(googleUrl).openConnection();
		connection.addRequestProperty("Referer", "http://stephane.galles.free.fr");
		var line: String = null
		val builder = new StringBuilder()
		val reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))
		line = reader.readLine()
		while(line != null) {
			 builder.append(line)
			line = reader.readLine()
		}

		
		JSON.parse(builder.toString())

	}

}


class FeedRepository(urls: List[URL]){

	class XmlInfo(xmlString: String){

		val elem: Node = XML.load(new StringReader(xmlString)) 		
				
		def url(title: String): Option[String] = {

			def clean(s: String) = s.replaceAll("\\s+"," ")

			val cleanedTitle = clean(title)
			def findUrl(parent: Node): Option[String] = {
				for ( child <- parent.child){					
					child match {
						case <enclosure/> if clean((parent \ "title").find(_.prefix == null).get.text) == cleanedTitle => 
							return Some((child \ "@url").text)
						case _ => findUrl(child) match {
									   case s @ Some(_) => return s
									   case None => 
							                 } 
					
					}
				}
				None
				
			}

			findUrl(elem) 				
		}
	}

	
	def loadAlbums() = {		
		
		def jsonListToMap(jsonList: Any): Map[String,Any] = {
			val list = jsonList match { case l: List[_] => l}
			(Map[String,Any]() /: list) {
				(sum,x) => sum + (x match { case (key: String, value: Any) => (key,value)})
			}
		}
		
		def extractTracksFromEntries(tracks: List[Any], xmlInfo: XmlInfo): List[Track] = {			
			
			for( jsonTrack <- tracks;
			     track = jsonListToMap(jsonTrack);
			     title: String = Any2String(track("title"));
			     url <- xmlInfo.url(title);	
			     pubDate: Date = Any2Date(track("publishedDate"))			      
			   ) yield(new Track(title,url,pubDate,0))
                                              							
		}

		def extractAlbumFromFeed(feedMap: Map[String,Any], xmlInfo: XmlInfo) = {			
			val album = feedMap("title") match { case album:String => album}
			val tracks = extractTracksFromEntries(feedMap("entries") match { case entries:List[_] => entries}, xmlInfo)
			new Album(album,tracks)
		}

		def extractAlbum(url: URL) = {

			println(url)
			new FeedReader().read(url) match {
				case Some( ("responseData",responseData) :: _) => 
					val responseDataMap = jsonListToMap(responseData)
					val xmlInfo = new XmlInfo(responseDataMap("xmlString") match { case x: String => x});
					extractAlbumFromFeed(jsonListToMap(responseDataMap("feed")), xmlInfo)					
				case None | Some(Nil) => throw new IllegalStateException()								
			}		
		}
				
		for(url <- urls)
			yield(extractAlbum(url))
	}
}

trait DeviceRepository{
	
	def saveTrack(albumName: String, track: Track)

	def deleteTrack(albumName: String, track: SimpleTrack)

	def loadAlbums(): List[DeviceAlbum]
	
}

class FilesRepository(rootDir: File) extends DeviceRepository {
	
	import org.jaudiotagger.audio.mp3._
	import org.jaudiotagger.tag.id3._	
	import org.jaudiotagger.audio._
	
	require(rootDir.exists,"rootDir does not exist : " + rootDir.getAbsolutePath)
	require(rootDir.isDirectory,"rootDir must be a directory : " + rootDir.getAbsolutePath)

	// TODO simplify RegExp
	val Url = """\s*(http\S+)\s*""".r					

	val onlyMp3 = new FileFilter(){
		def accept(file: File) = file.getName.toUpperCase.endsWith(".MP3")
	}

	
	def updateMp3Tag(trackFile: File, albumName: String, track: Track){
		val mp3file = AudioFileIO.read(trackFile) match {case m:MP3File => m}
		val v2tag = new ID3v24Tag()
		v2tag.setAlbum(albumName)
		v2tag.setTitle(track.title)
		v2tag.setComment(DateParser.format(track.pubDate))
		mp3file.setID3v2Tag(v2tag)
		mp3file.save()
	}
			
	def saveAlbums(albums: List[Album]){
		for( album <- albums ){
			println("Saving Album : " + album.name)			
			for( track <- album.tracks ){
				saveTrack(album.name, track)			
			}
		}
	}

	def saveTrack(albumName: String, track: Track){
		val albumDir = new File(rootDir,albumName)
		if(!albumDir.exists) albumDir.mkdir()
		val trackFile = new File(albumDir,track.filename)
		if(!trackFile.exists){
			println("Saving Track : " + track)	
			track.link match { 
				case Url(_) => 
					val command = "wget" :: "-q" :: "-r" :: "3" :: 
					      "-O" :: track.filename :: track.link :: Nil						
					retryWhile(trackFile.length == 0) {exec(command,albumDir)}
					updateMp3Tag(trackFile, albumName, track)
				case _ => 
					val command = "cp" :: track.link :: albumDir.getAbsolutePath :: Nil
					exec(command,albumDir)
			}
		}else{
			println("Skipping Track : " + track)	
		}
	}
		
	def loadAlbums() : List[Album]= rootDir.listFiles.filter(_.isDirectory)
						.map{ albumDir =>
							val tracks = albumDir.listFiles(onlyMp3).map{ trackFile =>
								try{								
									val mp3file = AudioFileIO.read(trackFile) match {case m:MP3File => m}
									val v2Tag = mp3file.getID3v2TagAsv24
									Some(new Track(v2Tag.getFirstTitle,
										  trackFile.getAbsolutePath,
										  DateParser.parse(v2Tag.getFirstComment),
										  mp3file.getAudioHeader.getTrackLength))
								} catch {
									case e: Exception =>
									println("Unable to read ID3 tag from " + trackFile + " reason :" + e) 
									trackFile.delete()								
									None
								}
							}.toList
							val survivorTracks = for(Some(t) <- tracks) yield t
							new Album(albumDir.getName,survivorTracks)
						 }.toList
					


	def deleteTrack(albumName: String, track: SimpleTrack){
		// TODO remove duplicate instanciation
		val albumDir = new File(rootDir,albumName)					   
		val trackFile = new File(albumDir,track.filename)			
		trackFile.delete()
	}
}



class MtpDeviceRepository extends DeviceRepository{

	private class MtpTrack(val id: String,val album: String,val title: String, val filename: String){
		
		override def toString = "MtpTrack id: " + id + "\nAlbum: " + album + "\nTitle: " + title + "\nFilename: " + filename + "\n"
	}

	private val TrackId = """Track ID: ([0-9]+)""".r
	private val Title = """ *Title: (.+)""".r
	private val Album = """ *Album: (.+)""".r
	private val Filename = """ *Origfilename: (.+)""".r
	private val dateFormat = new SimpleDateFormat("MM.dd");
	private val UrlTrackId = """([0-9]+)/.+""".r		

	private def extractId(uri: String): String = uri match { case UrlTrackId(i) => i}
	
	private def collect(lines: List[String],tracks: List[MtpTrack]): List[MtpTrack] = {
		lines match{
			case TrackId(id) :: Title(title) :: _ :: _:: Album(album) :: _ :: Filename(filename) :: rest => 			
				collect(rest,new MtpTrack(id,album,title,filename) :: tracks)
			case _ :: rest => collect(rest,tracks)
			case Nil => tracks
		}
	}

	private def mtpTrackstoAlbums(mtpTracks: List[MtpTrack]): List[DeviceAlbum] = {
		val emptyMap = Map[String,List[SimpleTrack]]().withDefaultValue(List[SimpleTrack]())
		val tracksByAlbum = (emptyMap /: mtpTracks) { (m,tr) =>
			val tracks = new SimpleTrack(tr.title,"" + tr.id + "/" + tr.filename) :: m(tr.album) 
			m + (tr.album -> tracks)
		}
		tracksByAlbum.map( (pair) => new DeviceAlbum(pair._1,pair._2)).toList
		
	}

	def saveTrack(albumName: String, track: Track){
		val datedTitle = dateFormat.format(track.pubDate) + " " + track.title
		val command = "mtp-sendtr" :: "-q" ::
				   "-t" :: datedTitle :: "-a" :: albumName :: "-A" :: albumName :: "-w" :: albumName ::
				   "-l" :: albumName :: "-c" :: "ISO MPEG-1 Audio Layer 3" :: "-g" :: "Podcast" ::
				   "-n" :: "1" :: "-y" :: "1970" :: "-d" :: track.length.toString ::
				   track.link :: track.filename :: Nil 	
					   
		exec(command,new File("/tmp"))
	}

	
	def deleteTrack(albumName: String, track: SimpleTrack){
		val command = "mtp-delfile" :: "-n" :: extractId(track.link) :: Nil 	
					   
		exec(command,new File("/tmp"))
	}


	def loadAlbums(): List[DeviceAlbum] = {
		val lines = exec("mtp-tracks" :: Nil,new File("/tmp")).map(_.stripLineEnd)		
		val mtpTracks = collect(lines,Nil)
		mtpTrackstoAlbums(mtpTracks)
	}
	
	
}


class Jonction(remote: FeedRepository, local: FilesRepository, device: DeviceRepository){

	import scala.collection.immutable.TreeSet
	import scala.util.Sorting
	
	
	val retainNumber = 4

	abstract class SyncableTrack extends Ordered[SyncableTrack]{
		
		def pubDate(): Option[Date]

		def copyToDevice(albumName: String)

		def deleteFromDevice(albumName: String)

		def compare(other: SyncableTrack) = {
			val ascComparaison = (pubDate,other.pubDate) match {
				case (Some(d1:Date),Some(d2:Date)) => d1.compareTo(d2)
				case (Some(_:Date),None) => 1
				case (None,Some(_:Date)) => -1
				case (None,None) => 0
			}
			ascComparaison * -1
		}

	}

	class SyncedTrack(tr: Track,devTr: SimpleTrack) extends SyncableTrack{
		def copyToDevice(albumName: String) {
			//println("Synced -> Copy -> Already on device ! " + tr)
		}
		def deleteFromDevice(albumName: String) {			
			println("Synced -> Delete -> Deleting... " + devTr)
			device.deleteTrack(albumName,devTr)
		}

		def pubDate(): Option[Date] = Some(tr.pubDate)

		override def toString = "Synced || local : " + tr + " || remote : " +  devTr
		
	}

	class LocalTrack(tr: Track) extends SyncableTrack{
		def copyToDevice(albumName: String) {
			println("Local -> Copy -> Copying... : " + tr)
			device.saveTrack(albumName,tr)
		}
		def deleteFromDevice(albumName: String) {
			//println("Local -> Delete -> Not on device ! " + tr)
		}
		
		def pubDate(): Option[Date] = Some(tr.pubDate)

		override def toString = "Local || local : " + tr
	}

	class RemoteTrack(devTr: SimpleTrack) extends SyncableTrack{
		def copyToDevice(albumName: String) {
			//println("Remote -> Copy -> Only on device ! " + devTr)
		}
		def deleteFromDevice(albumName: String) {
			println("Remote -> Delete -> Deleting... " + devTr)
			device.deleteTrack(albumName,devTr)
		}
		
		def pubDate(): Option[Date] = None

		override def toString = "Remote || remote : " + devTr
	}

	class SyncAlbum(name: String, syncTrack: List[SyncableTrack]){

		val arraySyncTrack = syncTrack.toArray
		Sorting.quickSort(arraySyncTrack)

		def sync(){
			//println("==>>> sync album " + name)
			for( (syncTrack,i) <- arraySyncTrack.toList.zipWithIndex){
				if(i < retainNumber)
					syncTrack.copyToDevice(name)
				else
					syncTrack.deleteFromDevice(name)
			}	
		}

		override def toString = "\nAlbum name: " + name + ( " " /: arraySyncTrack.toList)(_ + "\n" + _ + ",")

	}

	def syncRemoteToLocal() {
		local.saveAlbums(remote.loadAlbums)
	}

	def createSyncedSyncAlbum(src: Album, dest: DeviceAlbum): SyncAlbum = {
		
		// compute raw list		
		val srcTrByFilename = (Map[String,Track]() /: src.tracks) { (m,tr) => m + (tr.filename -> tr) }
		val destTrByFilename = (Map[String,SimpleTrack]() /: dest.tracks) { (m,tr) => m + (tr.filename -> tr) }
		
		// compute separated list
		val (syncedTracks, localTracks) = src.tracks.partition {tr => destTrByFilename.contains(tr.filename)}
		val remoteTracks = dest.tracks.filter{ tr => !srcTrByFilename.contains(tr.filename)}
		
		// reconstitute syncTracks
		val allSyncTracks = localTracks.map{ tr => new LocalTrack(tr)} :::
				    remoteTracks.map{ tr => new RemoteTrack(tr)} :::
				    syncedTracks.map{ tr => new SyncedTrack(tr,destTrByFilename(tr.filename))}
				 
		new SyncAlbum(src.name, allSyncTracks)
	}

	def createSyncAlbums(): List[SyncAlbum] = {

		// compute raw list
		val localAlbums = local.loadAlbums
		val deviceAlbums = device.loadAlbums
		val localAlbumsByName = (Map[String,Album]() /: localAlbums) { (m,alb) => m + (alb.name -> alb) }
		val deviceAlbumsByName = (Map[String,DeviceAlbum]() /: deviceAlbums) { (m,alb) => m + (alb.name -> alb) }		

		// compute separated list
		val (syncedAlbums,onlyLocalAlbums) = localAlbums.partition { a => deviceAlbumsByName.contains(a.name) }
		val onlyRemoteAlbums = deviceAlbums.filter{ a => !localAlbumsByName.contains(a.name)}
		
		// reconstitue SyncAlbums
		onlyLocalAlbums.map { a => new SyncAlbum(a.name, a.tracks.map(new LocalTrack(_)))} :::
		onlyRemoteAlbums.map { a => new SyncAlbum(a.name, a.tracks.map(new RemoteTrack(_)))}  :::
		syncedAlbums.map { a => createSyncedSyncAlbum(a,deviceAlbumsByName(a.name))}
	}

	def syncLocalToDevice() {
		val syncAlbums = createSyncAlbums()
		for(a <- syncAlbums){
			a.sync()
			
		}		
	}	

}

class UrlRepository(file: File){
		
	val Url = """\s*(http\S+)\s*""".r	
	
	def getAll() = {
		val lines = Source.fromFile(file).getLines.map(_.stripLineEnd).toList
		for(Url(u) <- lines) yield new URL(u)
	}

}

object DeviceType extends Enumeration {
	type DeviceType = Value
	val Mtp,FileSystem = Value
}


class Configuration(confFile: File){
	

	import scala.collection.jcl
	import java.util.Collections
	
	require(confFile.exists,"configuration file does not exist : " + confFile.getAbsolutePath)
	private val prop = new Properties();
	prop.load(new FileInputStream(confFile));

	private def replaceSystemProperties(rawValue: String) = {
		val sysProp = System.getProperties		
		val sysPropNamesArrayList = Collections.list(sysProp.propertyNames)
		val sysPropNames = new jcl.ArrayList(sysPropNamesArrayList).toList.map(_ match {case s: String => s})
		(rawValue /: sysPropNames) { (rv,spn) => rv.replace("${"+spn+"}", sysProp.getProperty(spn)) }
	}

	private def getProperty(name: String) = {
		val value = prop.getProperty(name);
		require(value != null,"Configuration property not found : "  + name)	
		replaceSystemProperties(value)
	}

	def downloadDir() = new File(getProperty("download.dir"))
	def urlsFile() = new File(getProperty("urls.file"))

	
	def deviceType() = getProperty("device.type") match {
				case "mtp" => DeviceType.Mtp
				case "fs" => DeviceType.FileSystem
			   }
		
	def deviceFileSystemDir() = new File(getProperty("device.fs.dir"))
		
	

	


}

//==============================================
//                   Main
//==============================================



// Args
val confFile = new File( if(args.length == 0) "jonction.properties" else args(0))

// Instantiate
val conf = new Configuration(confFile)
val urls = new UrlRepository(conf.urlsFile).getAll
val remote = new FeedRepository(urls)
val local = new FilesRepository(conf.downloadDir)
val device = conf.deviceType match {
	case DeviceType.Mtp => new MtpDeviceRepository()
	case DeviceType.FileSystem => new FilesRepository(conf.deviceFileSystemDir)
}
val jonction = new Jonction(remote,local,device)

// Execute
initJaudiotagger()
jonction.syncRemoteToLocal()
jonction.syncLocalToDevice()








