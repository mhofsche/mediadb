package controllers

import javax.inject.Inject

import models.Movie
import models.MovieFormats._
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import play.api.Play.current
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._
import play.api.mvc.{Action, Controller}
import play.modules.reactivemongo.MongoController
import play.modules.reactivemongo.json.collection.JSONCollection
import services.{FileService, MovieDBService}

import scala.concurrent.Future

/**
 * Controller for handling the TV Shows related stuff
 */
class TVShows @Inject()(fs: FileService, mdb: MovieDBService) extends Controller with MongoController {
  private final val logger: Logger = LoggerFactory.getLogger("movies")

  private def collection: JSONCollection = db.collection[JSONCollection]("tvshows")

  private val config: Configuration = current.configuration
  private val tvShowDirs: Seq[String] = config.getStringSeq("application.tvSources")
    .getOrElse(throw current.configuration.globalError("tvSources config not set"))

  private def allShowsFromDisk: Seq[Movie] = {
    tvShowDirs.flatMap(dir => fs.getTVShowsFromDisk(dir))
  }

  private def allShowsFromDB: Future[Seq[Movie]] = {
    collection.
      find(Json.obj()).
      sort(Json.obj("name" -> 1)). // maybe do client side sorting later
      cursor[Movie].
      collect[Seq]()
  }

  private def showsWithoutDetails: Future[Seq[Movie]] = {
    collection.
      find(Json.obj("details" -> Json.obj())).
      cursor[Movie].
      collect[Seq]()
  }

  case class MovieSyncResult(addedMovies: Seq[Movie], deletedMovies: Seq[Movie])

  private def findAllNewAndDeleted: Future[MovieSyncResult] = {
    val moviesFromDisk: Seq[Movie] = allShowsFromDisk
    val futureMovieList: Future[Seq[Movie]] = allShowsFromDB

    futureMovieList.map { fm =>
      MovieSyncResult(moviesFromDisk.diff(fm), fm.diff(moviesFromDisk))
    }
  }

  def syncDbWithFiles = Action.async {
    val futureSync: Future[MovieSyncResult] = findAllNewAndDeleted
    futureSync.map { result =>
      for (movie <- result.addedMovies) {
        collection.insert(movie).map { lastError =>
          val msg = "Success: " + lastError.ok.toString + " added: " + movie.name
          logger.info(msg)
          if (!lastError.ok) {

          }
        }
      }
      for (movie <- result.deletedMovies) {
        collection.remove(Json.obj("name" -> movie.name)).map { lastError =>
          val msg = "Success: " + lastError.ok.toString + " deleted: " + movie.name
          logger.info(msg)
        }
      }
      Ok("Synchronizing db with filesystem")
    }
  }

  def fetchShowDetails = Action.async {
    val toBeUpdated: Future[Seq[Movie]] = showsWithoutDetails
    toBeUpdated.map { shows =>
      for (show <- shows) {
        mdb.searchTVShow(show.name).map { searchRes =>
          val showId = (searchRes \ "results")(0) \ "id"
          if (showId.isInstanceOf[JsNumber]) {
            mdb.loadTVShowDetails(showId.as[Int], "en").map { showDetails =>
              val oid = Json.obj("_id" -> Json.obj("$oid" -> show.id))
              val details = Json.obj("$set" -> Json.obj("details" -> showDetails))
              collection.update(oid, details).map { lastError =>
                val msg = "Success: " + lastError.ok.toString + " updated: " + show.name
                logger.info(msg)
              }
            }
          }
        }
      }
      Ok
    }
  }

  def listTVShows = Action.async {
    allShowsFromDB map { shows =>
      Ok(Json.toJson(shows))
    }
  }

  def tvShowDetails(name: String) = Action.async {
    val fm: Future[Option[Movie]] = collection.find(Json.obj("name" -> name)).one

    fm.map {
      case Some(show) => Ok(Json.toJson(show))
      case None => NotFound(Json.obj("error" -> "not found"))

    }
  }

/* old version
  def listEpisodes(tvShowName: String, seasonNumber: Int) = Action { implicit request =>
    request.getQueryString("fp") match {
      case Some(path) =>
        val episodes: Seq[Movie] = fs.getTVShowEpisodes(path, seasonNumber)
        Ok(Json.toJson(episodes))
      case None => NotFound
    }
  }
*/

  def listEpisodes(tvShowName: String) = Action.async {
    val fm: Future[Option[Movie]] = collection.find(Json.obj("name" -> tvShowName)).one
    fm.map {
      case Some(show) =>
        val seasons: Seq[JsValue] = show.details \ "seasons" \\ "season_number"
        val episodes: Seq[JsValue] = seasons.map(s =>
          Json.obj(
            "season_number" -> s,
            "episodes" -> fs.getTVShowEpisodes(show.filePath, s.as[Int]))
          )

        Ok(Json.toJson(episodes))

      case None => NotFound
    }
  }


}
