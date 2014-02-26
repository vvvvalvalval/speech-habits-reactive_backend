package controllers

import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.postfixOps
import akka.actor.Props
import akka.pattern.ask
import akka.util.Timeout
import models.Expression
import models.Student
import play.Logger.info
import play.api.Play.current
import play.api.libs.Jsonp
import play.api.libs.concurrent.Akka
import play.api.libs.iteratee.Enumerator
import play.api.libs.iteratee.Iteratee
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import play.api.libs.json.Json.toJsFieldJsValueWrapper
import play.api.mvc.Action
import play.api.mvc.Controller
import play.api.mvc.WebSocket
import models.Student
import models.Student

object Application extends Controller {

  implicit val timeout = Timeout(5000)
  val teacherRoom = Akka.system(current).actorOf(Props[TeacherRoom], "teacher-room")
  val studentsActor = Akka.system(current).actorOf(Props[StudentsActor], "students")
  
  def index = Action {
    Ok(views.html.index("Your new application is ready."))
  }

  /**
   * For obtaining a new WebSocket connection associated to a Student.
   */
  def studentSocket(pseudo: String) = {
    val student = Student(pseudo)
    
    info("Requesting WebSocket connection for " + pseudo)
    val ws = WebSocket.async { request =>
      val channelsFuture = studentsActor ? RequestNewConnectionFor(student)
      val f = channelsFuture.mapTo[((Iteratee[JsValue, _], Enumerator[JsValue]))]
      
      info("Sending WebSocket connection for " + student)
      f
    }
    ws
  }

//  def expressions(callback: String) = {
//
//	import play.Logger._
//	
//	info("Sending the expressions data...")
//    
//    import scala.concurrent.ExecutionContext.Implicits.global
//
//    val futureCounters =
//      (teacherRoom ? GiveCountersSnapshot)
//        .mapTo[Map[Expression, Int]]
//
//    val futureAction = futureCounters.collect {
//      case expressionsCounters: Map[Expression, Int] => {
//
//        val result: JsValue = Json.toJson(expressionsCounters.map(expressionToJson(_)))
//
//        debug("Sent the expressions data.")
//        Ok(Jsonp(callback,result)) //TODO return appropriate response
//      }
//    }
//
//    Action.async(futureAction)
//  }
//
//  def expressionToJson(expressionCount: (Expression, Int)): JsValue = expressionCount match {
//    case (expression, count) => Json.obj(
//      "id" -> expression.id,
//      "text" -> expression.text,
//      "count" -> count)
//  }
}