package controllers

import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.postfixOps
import akka.actor.Props
import akka.pattern.ask
import akka.util.Timeout
import play.api.Play.current
import play.api.libs.concurrent.Akka
import play.api.libs.iteratee.Enumerator
import play.api.libs.iteratee.Iteratee
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import play.api.libs.json.Json.toJsFieldJsValueWrapper
import play.api.mvc.Action
import play.api.mvc.Controller
import play.api.mvc.WebSocket
import play.api.libs.iteratee.Concurrent
import play.api.libs.Jsonp

object Application extends Controller {

  implicit val timeout = Timeout(1000)
  val teacherRoom = Akka.system(current).actorOf(Props[TeacherRoom], "teacher-room")

  def index = Action {
    Ok(views.html.index("Your new application is ready."))
  }

  def studentSocket(pseudo: String) = {
    val ws = WebSocket.async { request =>
      val channelsFuture = teacherRoom ? TeacherRoomMessage.Join(Student(pseudo))
      val f = channelsFuture.mapTo[((Iteratee[JsValue, _], Enumerator[JsValue]))]
      f
    }
    ws
  }

  def expressions(callback: String) = {

	import play.Logger._
	
	info("Sending the expressions data...")
    
    import scala.concurrent.ExecutionContext.Implicits.global

    val futureExpr =
      (teacherRoom ? TeacherRoomMessage.GiveExpressions)
        .mapTo[Map[Expression, Int]]

    val futureAction = futureExpr.collect {
      case expressionsCounters: Map[Expression, Int] => {

        val result: JsValue = Json.toJson(expressionsCounters.map(expressionToJson(_)))

        Ok(Jsonp(callback,result)) //TODO return appropriate response
      }
    }

    Action.async(futureAction)
  }

  private def expressionToJson(expressionCount: (Expression, Int)): JsValue = expressionCount match {
    case (expression, count) => Json.obj(
      "id" -> expression.id,
      "text" -> expression.text,
      "count" -> count)
  }
}