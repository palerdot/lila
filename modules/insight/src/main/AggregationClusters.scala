package lila.insight

import reactivemongo.api.collections.bson.BSONBatchCommands.AggregationFramework._
import reactivemongo.bson._

object AggregationClusters {

  def apply[X](question: Question[X], res: AggregationResult): List[Cluster[X]] =
    postSort(question) {
      if (Metric isStacked question.metric) stacked(question, res)
      else single(question, res)
    }

  private def single[X](question: Question[X], res: AggregationResult): List[Cluster[X]] =
    res.documents.flatMap { doc =>
      for {
        x <- doc.getAs[X]("_id")(question.dimension.bson)
        value <- doc.getAs[BSONNumberLike]("v")
        nb <- doc.getAs[Int]("nb")
      } yield Cluster(x, Insight.Single(Point(value.toDouble)), nb)
    }

  private case class StackEntry(metric: Int, v: BSONNumberLike)
  private implicit val StackEntryBSONReader = Macros.reader[StackEntry]

  private def stacked[X](question: Question[X], res: AggregationResult): List[Cluster[X]] =
    res.documents.flatMap { doc =>
      val metricValues = Metric valuesOf question.metric
      for {
        x <- doc.getAs[X]("_id")(question.dimension.bson)
        stack <- doc.getAs[List[StackEntry]]("stack")
        points = metricValues.map {
          case Metric.MetricValue(id, name) =>
            name -> Point(stack.find(_.metric == id).??(_.v.toDouble))
        }
        total = stack.map(_.v.toInt).sum
        percents = if (total == 0) points
        else points.map {
          case (n, p) => n -> Point(100 * p.y / total)
        }
      } yield Cluster(x, Insight.Stacked(percents), total)
    }

  private def postSort[X](q: Question[X])(clusters: List[Cluster[X]]): List[Cluster[X]] = q.dimension match {
    case Dimension.Opening => clusters
    case _                 => sortLike[Cluster[X], X](clusters, Dimension.valuesOf(q.dimension), _.x)
  }

  private def sortLike[A, B](la: List[A], lb: List[B], f: A => B): List[A] = la.sortWith {
    case (x, y) => lb.indexOf(f(x)) < lb.indexOf(f(y))
  }
}