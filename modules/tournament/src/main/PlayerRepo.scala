package lila.tournament

import org.joda.time.DateTime
import reactivemongo.bson._
import reactivemongo.core.commands._

import BSONHandlers._
import lila.db.BSON._
import lila.db.Implicits._
import lila.rating.Perf
import lila.user.{ User, Perfs }

object PlayerRepo {

  private lazy val coll = Env.current.playerColl

  private def selectId(id: String) = BSONDocument("_id" -> id)
  private def selectTour(tourId: String) = BSONDocument("tid" -> tourId)
  private def selectUser(userId: String) = BSONDocument("uid" -> userId)
  private def selectTourUser(tourId: String, userId: String) = BSONDocument(
    "tid" -> tourId,
    "uid" -> userId)
  private val selectActive = BSONDocument("w" -> BSONDocument("$ne" -> true))
  private val selectWithdraw = BSONDocument("w" -> true)
  private val bestSort = BSONDocument("m" -> -1)

  def byId(id: String): Fu[Option[Player]] = coll.find(selectId(id)).one[Player]

  def bestByTour(tourId: String, nb: Int, skip: Int = 0): Fu[List[Player]] =
    coll.find(selectTour(tourId)).sort(bestSort).skip(skip).cursor[Player]().collect[List](nb)

  def bestByTourWithRank(tourId: String, nb: Int, skip: Int = 0): Fu[RankedPlayers] =
    bestByTour(tourId, nb, skip).map { res =>
      res.foldRight(List.empty[RankedPlayer] -> (res.size + skip)) {
        case (p, (res, rank)) => (RankedPlayer(rank, p) :: res, rank - 1)
      }._1
    }

  def bestByTourWithRankByPage(tourId: String, nb: Int, page: Int): Fu[RankedPlayers] =
    bestByTourWithRank(tourId, nb, (page - 1) * nb)

  def countActive(tourId: String): Fu[Int] =
    coll.count(Some(selectTour(tourId) ++ selectActive))

  def count(tourId: String): Fu[Int] = coll.count(Some(selectTour(tourId)))

  def removeByTour(tourId: String) = coll.remove(selectTour(tourId)).void

  def remove(tourId: String, userId: String) =
    coll.remove(selectTourUser(tourId, userId)).void

  def exists(tourId: String, userId: String) =
    coll.count(selectTourUser(tourId, userId).some) map (0!=)

  def existsActive(tourId: String, userId: String) =
    coll.count(Some(
      selectTourUser(tourId, userId) ++ selectActive
    )) map (0!=)

  def unWithdraw(tourId: String) = coll.update(
    selectTour(tourId) ++ selectWithdraw,
    BSONDocument("$unset" -> BSONDocument("w" -> true)),
    multi = true).void

  def find(tourId: String, userId: String): Fu[Option[Player]] =
    coll.find(selectTourUser(tourId, userId)).one[Player]

  def update(tourId: String, userId: String)(f: Player => Fu[Player]) =
    find(tourId, userId) flatten s"No such player: $tourId/$userId" flatMap f flatMap { player =>
      coll.update(selectId(player._id), player).void
    }

  def playerInfo(tourId: String, userId: String): Fu[Option[PlayerInfo]] = find(tourId, userId) flatMap {
    _ ?? { player =>
      coll.count(Some(selectTour(tourId) ++ BSONDocument(
        "m" -> BSONDocument("$gt" -> player.magicScore))
      )) map { n =>
        PlayerInfo((n + 1), player.withdraw).some
      }
    }
  }

  def join(tourId: String, user: User, perfLens: Perfs => Perf) =
    find(tourId, user.id) flatMap {
      case Some(p) if p.withdraw => coll.update(selectId(p._id), BSONDocument("$unset" -> BSONDocument("w" -> true)))
      case Some(p)               => funit
      case None                  => coll.insert(Player.make(tourId, user, perfLens))
    } void

  def withdraw(tourId: String, userId: String) = coll.update(
    selectTourUser(tourId, userId),
    BSONDocument("$set" -> BSONDocument("w" -> true))).void

  def withPoints(tourId: String): Fu[List[Player]] =
    coll.find(
      selectTour(tourId) ++ BSONDocument("m" -> BSONDocument("$gt" -> 0))
    ).cursor[Player]().collect[List]()

  private def aggregationUserIdList(res: Stream[BSONDocument]): List[String] =
    res.headOption flatMap { _.getAs[List[String]]("uids") } getOrElse Nil

  import coll.BatchCommands.AggregationFramework, AggregationFramework.{ Descending, Group, Match, Push, Sort }

  def userIds(tourId: String): Fu[List[String]] =
    coll.aggregate(Match(selectTour(tourId)), List(
      Group(BSONBoolean(true))("uids" -> Push("uid")))).
      map(res => aggregationUserIdList(res.documents.toStream))

  def activeUserIds(tourId: String): Fu[List[String]] =
    coll.aggregate(Match(selectTour(tourId) ++ selectActive), List(
      Group(BSONBoolean(true))("uids" -> Push("uid")))).
      map(res => aggregationUserIdList(res.documents.toStream))

  def winner(tourId: String): Fu[Option[Player]] =
    coll.find(selectTour(tourId)).sort(bestSort).one[Player]

  // freaking expensive (marathons)
  private[tournament] def computeRanking(tourId: String): Fu[Ranking] =
    coll.aggregate(Match(selectTour(tourId)), List(Sort(Descending("m")),
      Group(BSONBoolean(true))("uids" -> Push("uid")))).
      map(res => aggregationUserIdList(res.documents.toStream)).
      map { _.zipWithIndex.toMap }

  def byTourAndUserIds(tourId: String, userIds: Iterable[String]): Fu[List[Player]] =
    coll.find(selectTour(tourId) ++ BSONDocument(
      "uid" -> BSONDocument("$in" -> userIds)
    )).cursor[Player]().collect[List]()

  def rankPlayers(players: List[Player], ranking: Ranking): RankedPlayers =
    players.flatMap { p =>
      ranking get p.userId map { RankedPlayer(_, p) }
    }.sortBy(-_.rank)

  def rankedByTourAndUserIds(tourId: String, userIds: Iterable[String], ranking: Ranking): Fu[RankedPlayers] =
    byTourAndUserIds(tourId, userIds) map { rankPlayers(_, ranking) }
}
