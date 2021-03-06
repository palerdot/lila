package lila.round

import reactivemongo.bson._

import lila.db.BSON.BSONJodaDateTimeHandler
import lila.db.Implicits._
import org.joda.time.DateTime
import scala.concurrent.duration.Duration
import scala.concurrent.Promise

import chess.format.UciMove
import chess.Pos
import Forecast.Step
import lila.game.{ Pov, Game }
import lila.hub.actorApi.map.Tell

final class ForecastApi(coll: Coll, roundMap: akka.actor.ActorSelection) {

  private implicit val PosBSONHandler = new BSONHandler[BSONString, Pos] {
    def read(bsonStr: BSONString): Pos = Pos.posAt(bsonStr.value) err s"No such pos: ${bsonStr.value}"
    def write(x: Pos) = BSONString(x.key)
  }

  private implicit val stepBSONHandler = Macros.handler[Step]
  private implicit val forecastBSONHandler = Macros.handler[Forecast]
  import Forecast._

  private def saveSteps(pov: Pov, steps: Forecast.Steps): Funit = coll.update(
    BSONDocument("_id" -> pov.fullId),
    Forecast(
      _id = pov.fullId,
      steps = steps,
      date = DateTime.now).truncate,
    upsert = true).void

  def save(pov: Pov, steps: Forecast.Steps): Funit = firstStep(steps) match {
    case None => coll.remove(BSONDocument("_id" -> pov.fullId)).void
    case Some(step) if pov.game.turns == step.ply - 1 => saveSteps(pov, steps)
    case _ => fufail(Forecast.OutOfSync)
  }

  def playAndSave(
    pov: Pov,
    uciMove: String,
    steps: Forecast.Steps,
    ip: String): Funit =
    if (!pov.isMyTurn) fufail("not my turn")
    else UciMove(uciMove).fold[Funit](fufail(s"Invalid move $uciMove")) { uci =>
      val promise = Promise[Unit]
      roundMap ! Tell(pov.game.id, actorApi.round.HumanPlay(
        playerId = pov.playerId,
        ip = ip,
        orig = uci.orig.key,
        dest = uci.dest.key,
        prom = uci.promotion.map(_.name),
        blur = true,
        lag = Duration.Zero,
        promise = promise.some))
      saveSteps(pov, steps) >> promise.future
    }

  def loadForDisplay(pov: Pov): Fu[Option[Forecast]] =
    pov.forecastable ?? coll.find(BSONDocument("_id" -> pov.fullId)).one[Forecast] flatMap {
      case None => fuccess(none)
      case Some(fc) =>
        if (firstStep(fc.steps).exists(_.ply != pov.game.turns + 1)) clearPov(pov) inject none
        else fuccess(fc.some)
    }

  def loadForPlay(pov: Pov): Fu[Option[Forecast]] =
    pov.game.forecastable ?? coll.find(BSONDocument("_id" -> pov.fullId)).one[Forecast] flatMap {
      case None => fuccess(none)
      case Some(fc) =>
        if (firstStep(fc.steps).exists(_.ply != pov.game.turns)) clearPov(pov) inject none
        else fuccess(fc.some)
    }

  def nextMove(g: Game, last: chess.Move): Fu[Option[UciMove]] = g.forecastable ?? {
    loadForPlay(Pov player g) flatMap {
      case None => fuccess(none)
      case Some(fc) => fc(g, last) match {
        case Some((newFc, uciMove)) if newFc.steps.nonEmpty =>
          coll.update(BSONDocument("_id" -> fc._id), newFc) inject uciMove.some
        case Some((newFc, uciMove)) => clearPov(Pov player g) inject uciMove.some
        case _                      => clearPov(Pov player g) inject none
      }
    }
  }

  private def firstStep(steps: Forecast.Steps) = steps.headOption.flatMap(_.headOption)

  def clearGame(g: Game) = coll.remove(BSONDocument(
    "_id" -> BSONDocument("$in" -> chess.Color.all.map(g.fullIdOf))
  )).void

  def clearPov(pov: Pov) = coll.remove(BSONDocument("_id" -> pov.fullId)).void
}
