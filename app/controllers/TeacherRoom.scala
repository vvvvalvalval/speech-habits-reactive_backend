package controllers

import scala.collection.immutable.Map
import akka.actor.Actor
import akka.actor.actorRef2Scala
import play.Logger.info
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.iteratee.Concurrent
import play.api.libs.iteratee.Concurrent.Channel
import play.api.libs.iteratee.Enumerator
import play.api.libs.iteratee.Iteratee
import play.api.libs.json.JsObject
import play.api.libs.json.JsString
import play.api.libs.json.JsValue
import play.api.libs.concurrent.Promise

/**
 * An expression a teacher says over and over again.
 */
case class Expression(id: Long, text: String)

case class Student(pseudo: String)

sealed abstract class TeacherRoomMessage
object TeacherRoomMessage {
  case class Increment(expressionId: Long) extends TeacherRoomMessage
  case object GiveExpressions extends TeacherRoomMessage
  case class Join(student: Student) extends TeacherRoomMessage
  case class Connect(student: Student, channel: Channel[JsValue]) extends TeacherRoomMessage
  case class Leave(student: Student) extends TeacherRoomMessage
}
class TeacherRoom extends Actor {

  var expressionsCounters: Map[Expression, Int] = Map(
    Expression(0, "By the way") -> 9,
    Expression(1, "Somewhere, somehow") -> 5,
    Expression(3, "You notice that") -> 3
  )

  def findExpressionById(expressionId: Long): Expression = {
    expressionsCounters.keySet
      .find(_.id == expressionId)
      .getOrElse({ throw new NoSuchElementException("Nonexistent Expression ID : " + expressionId) })
  }

  /**
   * The data we keep when registering a student.
   */
  class StudentData(
    var channel: Option[Channel[JsValue]],
    var in: Iteratee[JsValue, _],
    var out: Enumerator[JsValue],
    var score: Double = 0.0)

  var loggedInStudents = Map[Student, StudentData]()

  def receive = {
    /**
     * To retrieve the list of expressions
     */
    case TeacherRoomMessage.GiveExpressions => {
      sender ! expressionsCounters

      info("Sent the expressions counters : " + expressionsCounters)
    }
    case TeacherRoomMessage.Increment(expressionId) => {
      val expression = findExpressionById(expressionId)
      expressionsCounters += expression -> (expressionsCounters(expression) + 1)

      info("Incremented expression : " + expression)
    }
    case TeacherRoomMessage.Join(student) => {
      if (!loggedInStudents.contains(student)) {

        //creating the iteratee from which messages are retrieved
        val in = Iteratee.foreach[JsValue] { message =>
          info("Just received from " + student.pseudo + " : " + message)
        }.map {_ =>
          self ! TeacherRoomMessage.Leave(student)
        }
        //creating channel-enumerator pair for pushing data to student
        //val (out, channel) = Concurrent.broadcast[JsValue]
        val out = Concurrent.unicast[JsValue]({ channel => //pushing a test message
          channel push jsRoomEventOf(ConnectEvent, JsString(student.pseudo + ", do you copy?"))
          info("Just pushed a message into the channel of " + student.pseudo)

          self ! TeacherRoomMessage.Connect(student, channel)
          info("sent connect message for " + student.pseudo)
        })

        //registering the student TODO race condition?
        loggedInStudents += student -> new StudentData(None, in, out, 0.0)
        info("Just created data for : " + student.pseudo)

      }
      
      loggedInStudents.get(student) map { studentData =>
        sender ! (studentData.in, studentData.out)
        info("Just sent iteratee and enumerator to create websocket for " + student.pseudo)
      }

    }
    /**
     * This message tells us the channel for the websocket is ready.
     */
    case TeacherRoomMessage.Connect(student, channel) => {
      loggedInStudents get student map { data =>
        data.channel = Some(channel)
        channel push jsRoomEventOf(ConnectEvent,JsString("Now you're connected"))
        info("Just connected " + student.pseudo)
      }
    }
    case TeacherRoomMessage.Leave(student) => {
      info("removing student " + student.pseudo)
      loggedInStudents -= student
    }
    case _ => ()
  }
  
  sealed abstract class RoomEventType(val typeName: String)
  case object ConnectEvent extends RoomEventType("connect")
  case object DescribeCountersEvent extends RoomEventType("describe-counters")
  
  def jsRoomEventOf(eventType: RoomEventType, data: JsValue): JsValue = {
    return JsObject(Seq(
    	"event-type" -> JsString(eventType.typeName),
    	"data" -> data
    ))
  }
  
}