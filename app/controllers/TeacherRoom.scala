package controllers

import scala.annotation.migration
import scala.collection.immutable.Map
import akka.actor.Actor
import akka.actor.actorRef2Scala
import play.Logger.info
import play.Logger.warn
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.iteratee.Concurrent
import play.api.libs.iteratee.Concurrent.Channel
import play.api.libs.iteratee.Enumerator
import play.api.libs.iteratee.Iteratee
import play.api.libs.json.JsArray
import play.api.libs.json.JsBoolean
import play.api.libs.json.JsNull
import play.api.libs.json.JsNumber
import play.api.libs.json.JsObject
import play.api.libs.json.JsString
import play.api.libs.json.JsUndefined
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import scala.util.Random

/**
 * An expression a teacher says over and over again.
 */
case class Expression(id: Long, text: String)

case class Student(pseudo: String)

sealed abstract class TeacherRoomMessage

object TeacherRoomMessage {

  case class AskIncrement(from: Student, expression: Expression) extends TeacherRoomMessage
  case class DenyIncrement(to: Student, expression: Expression) extends TeacherRoomMessage
  case class Increment(expression: Expression) extends TeacherRoomMessage

  case object GiveExpressions extends TeacherRoomMessage

  case class Join(student: Student) extends TeacherRoomMessage
  case class Connect(student: Student, channel: Channel[JsValue]) extends TeacherRoomMessage
  case class Leave(student: Student) extends TeacherRoomMessage
}
/**
 * The Actor managing the room.
 */
class TeacherRoom extends Actor {

  var expressionsCounters: Map[Expression, Int] = Map(
    Expression(0, "By the way") -> 9,
    Expression(1, "Somewhere, somehow") -> 5,
    Expression(3, "You notice that") -> 3)

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

  def reactToStudentMessage(student: Student)(message: JsValue) {
    info("Just received from " + student.pseudo + " : " + message)

    val messageJsContent = message \ "content"
    (message \ "message_type").as[String] match {
      case "ask-increment" => {
        val expression = findExpressionById((messageJsContent \ "expression_id").as[Long])
        self ! TeacherRoomMessage.AskIncrement(student, expression)
      }
      case _ => ()
    }

  }

  def isIncrementAccepted(expression: Expression, student: Student) = {
    import scala.util.Random
    
    val likelihood = 1.0
    //TODO stricter criteria for incrementing
    (Random.nextDouble() < likelihood)
  }

  def receive = {
    import controllers.TeacherRoomMessage._
    {

      /**
       * To retrieve the list of expressions
       */
      case GiveExpressions => {
        sender ! expressionsCounters

        info("Sent the expressions counters : " + expressionsCounters)
      }

      case Increment(expression) => {
        expressionsCounters += expression -> (expressionsCounters(expression) + 1)

        info("Incremented expression : " + expression)
        sendMessageToAll(newIncrementMessage(expression))
        //TODO notify students
      }

      case Join(student) => {
        if (!loggedInStudents.contains(student)) {

          //creating the iteratee from which messages are retrieved
          val in = Iteratee.foreach[JsValue] { message =>
            reactToStudentMessage(student)(message)
          }.map { _ =>
            //what happens when the iteratee is closed.
            self ! TeacherRoomMessage.Leave(student)
          }
          //creating channel-enumerator pair for pushing data to student
          //val (out, channel) = Concurrent.broadcast[JsValue]
          val out = Concurrent.unicast[JsValue]({ channel => //pushing a test message
            channel push jsRoomMessageOf(ConnectMessage, JsString(student.pseudo + ", do you copy?"))
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
      case Connect(student, channel) => {
        loggedInStudents get student map { data =>
          data.channel = Some(channel)
//          channel push jsRoomMessageOf(ConnectEvent, JsString("Now you're connected"))
          info("Just connected " + student.pseudo)
        }
        sendMessageToStudent(jsRoomMessageOf(ConnectMessage, JsString("Now you're connected")))(student)
      }

      case Leave(student) => {
        info("removing student " + student.pseudo)
        loggedInStudents -= student
      }

      case AskIncrement(student, expression) => {
        info(student.pseudo + " asked that expression '" + expression.text + "' be incremented")

        if (isIncrementAccepted(expression, student)) {
          self ! Increment(expression)
          //TODO reward student 
        } else {
          self ! DenyIncrement(student, expression)
        }
      }

      case DenyIncrement(student, expression) => {
        sendMessageToStudent(newDenyIncrementMessage(expression))(student)
      }
      
      case _ => ()
    }
  }

  sealed abstract class RoomMessageType(val typeName: String)
  case object ConnectMessage extends RoomMessageType("connect")
  case object DescribeCountersMessage extends RoomMessageType("describe-counters")
  case object IncrementMessage extends RoomMessageType("increment")
  case object DenyIncrementMessage extends RoomMessageType("deny-increment")

  def jsRoomMessageOf(messageType: RoomMessageType, content: JsValue): JsValue = {
    return JsObject(Seq(
      "message_type" -> JsString(messageType.typeName),
      "content" -> content))
  }

  def sendMessageToStudent(jsMessage: JsValue)(to: Student) {
    loggedInStudents.get(to).map(studentData => {
      studentData.channel match {
        case Some(channel) => {
          info("Sending to " + to.pseudo + " message " + jsMessage)
          channel.push(jsMessage)
        }
        case None => {
          warn("Could not send message " + jsMessage + " to student " + to.pseudo + " : not connected")
        }
      }
    })
  }
  
  def sendMessageToAll(jsMessage: JsValue){
    info("Broadcasting to all students : " + jsMessage)
    
    val sendMessage = sendMessageToStudent(jsMessage) _
    loggedInStudents.keys foreach sendMessage
  }
  
  def newIncrementMessage(expression: Expression): JsValue = {
    jsRoomMessageOf(IncrementMessage, Json.obj("expression_id" -> expression.id))
  }
  
  def newDenyIncrementMessage(expression: Expression): JsValue = {
    jsRoomMessageOf(DenyIncrementMessage, Json.obj("expression_id" -> expression.id))
  }
}