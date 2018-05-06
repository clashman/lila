package controllers

import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import play.api.mvc.RequestHeader
import scala.concurrent.duration._

import lila.api.GameApiV2
import lila.app._
import lila.common.{ MaxPerSecond, HTTPRequest }
import lila.game.{ GameRepo, Game => GameModel }
import views._

object Game extends LilaController {

  def delete(gameId: String) = Auth { implicit ctx => me =>
    OptionFuResult(GameRepo game gameId) { game =>
      if (game.pgnImport.flatMap(_.user) ?? (me.id==)) {
        Env.hub.actor.bookmark ! lila.hub.actorApi.bookmark.Remove(game.id)
        (GameRepo remove game.id) >>
          (lila.analyse.AnalysisRepo remove game.id) >>
          Env.game.cached.clearNbImportedByCache(me.id) inject
          Redirect(routes.User.show(me.username))
      } else fuccess {
        Redirect(routes.Round.watcher(game.id, game.firstColor.name))
      }
    }
  }

  def export(username: String) = OpenOrScoped()(
    open = ctx => handleExport(username, ctx.me, ctx.req, oauth = false),
    scoped = req => me => handleExport(username, me.some, req, oauth = true)
  )

  private def handleExport(username: String, me: Option[lila.user.User], req: RequestHeader, oauth: Boolean) =
    lila.user.UserRepo named username flatMap {
      _ ?? { user =>
        RequireHttp11(req) {
          Api.GlobalLinearLimitPerIP(HTTPRequest lastRemoteAddress req) {
            Api.GlobalLinearLimitPerUserOption(me) {
              val config = GameApiV2.Config(
                user = user,
                format = GameApiV2.Format.PGN,
                since = getLong("since", req) map { ts => new DateTime(ts) },
                until = getLong("until", req) map { ts => new DateTime(ts) },
                max = getInt("max", req) map (_ atLeast 1),
                rated = getBoolOpt("rated", req),
                perfType = ~get("perfType", req) split "," flatMap { lila.rating.PerfType(_) } toSet,
                color = get("color", req) flatMap chess.Color.apply,
                analysed = getBoolOpt("analysed", req),
                flags = lila.game.PgnDump.WithFlags(
                  moves = getBoolOpt("moves", req) | true,
                  tags = getBoolOpt("tags", req) | true,
                  clocks = getBoolOpt("clocks", req) | false,
                  evals = getBoolOpt("evals", req) | false,
                  opening = getBoolOpt("opening", req) | false
                ),
                perSecond = MaxPerSecond(me match {
                  case Some(m) if m is user.id => 50
                  case Some(_) if oauth => 20 // bonus for oauth logged in only (not for XSRF)
                  case _ => 10
                })
              )
              val date = (DateTimeFormat forPattern "yyyy-MM-dd") print new DateTime
              Ok.chunked(Env.api.gameApiV2.exportUserGames(config)).withHeaders(
                CONTENT_TYPE -> pgnContentType,
                CONTENT_DISPOSITION -> ("attachment; filename=" + s"lichess_${user.username}_$date.pgn")
              ).fuccess
            }
          }
        }
      }
    }

  private[controllers] def preloadUsers(game: GameModel): Funit =
    Env.user.lightUserApi preloadMany game.userIds
}
