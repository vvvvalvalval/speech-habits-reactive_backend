package controllers

import scala.collection.mutable.{ Map => Map, Set => Set }
import scala.util.Random
import akka.actor.Actor
import models.Expression
import models.Student
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.iteratee.Concurrent
import play.api.libs.iteratee.Concurrent.Channel
import play.api.libs.iteratee.Enumerator
import play.api.libs.iteratee.Iteratee
import play.api.libs.json.JsObject
import play.api.libs.json.JsString
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import play.api.libs.json.Json.toJsFieldJsValueWrapper
import models.Student
import models.StudentTerminal
import models.Student
import play.api.libs.concurrent.Promise
import java.util.concurrent.TimeUnit

/**
 * The Actor managing the room.
 */
class TeacherRoom extends Actor with SpeechHabitsLogged{

  val expressionsCounters: Map[Expression, Int] = Map(
    Expression(0, "By the way") -> 9,
    Expression(1, "Somewhere, somehow") -> 5,
    Expression(3, "You notice that") -> 3)

  def countersSnapshot: scala.collection.immutable.Map[Expression, Int] = expressionsCounters.toMap
    
  def findExpressionById(expressionId: Long): Expression = {
    expressionsCounters.keySet
      .find(_.id == expressionId)
      .getOrElse({ throw new NoSuchElementException("Nonexistent Expression ID : " + expressionId) })
  }

  class StudentRoomState(val student: Student) {
    var score: Double = 0.0
    val presentTerminals: Set[StudentTerminal] = Set()
  }

  val presentStudents: Map[Student, StudentRoomState] = Map()

  def receive =  {
    case message: Any => debug("Room received message : " + message)
    message match
    {
      /**
       * An external sender requests a snapshot of the expressions.
       * TODO remove?
       */
      case GiveCountersSnapshot => {
        sender ! countersSnapshot
      }
      
      /**
       * A terminal is added to the room, the student is added if necessary
       */
      case JoinRoom(studentTerminal) => {
        val student = studentTerminal.student
        presentStudents get student match {
          case None => {
            presentStudents(student) = new StudentRoomState(student)
            info("Added " + student + " to room")
          }
          case Some(_) => ()
        }

        val terminals = presentStudents(student).presentTerminals
        if (terminals.contains(studentTerminal)) {
          //nothing to do, it's already there
        } else {
          terminals add studentTerminal
          info("Added " + studentTerminal + " to room")
        }

        //sending state update
        studentsActor ! StateUpdate(studentTerminal, countersSnapshot, presentStudents(student).score)
        debug("Sending room state update to " + studentTerminal)
      }

      /**
       * A terminal is removed from the room
       */
      case LeaveRoom(studentTerminal) => {
        val student = studentTerminal.student
        presentStudents get student match {
          case None => {
            warn(studentTerminal + " wants to leave room, whereas its student is not in the room")
          }
          case Some(studentRoomState) => {
            info(studentTerminal + "leaves room")

            val wasThere = studentRoomState.presentTerminals remove studentTerminal
            if (!wasThere) {
              warn(studentTerminal + "was not in room")
            }

            if (studentRoomState.presentTerminals.isEmpty) {
              info("No more terminals for " + student + " in room.")

              //TODO : react to that. Removed after a certain time?
              doLater(5000) {
                info("I've waited 5000 ms, I was sent from " + student) //TODO remove that
                () //for example, send a message to check if has been present.
              }
            }
          }
        }
      }

      case AskIncrement(studentTerminal, expressionId, date) => {
        val expression = findExpressionById(expressionId)
        info(studentTerminal + " asked that " + expression + " be incremented at " + date)

        //TODO trivial implementation for now
        //incrementing and broadcasting the increment message
        info("Incrementing " + expression)
        expressionsCounters(expression) += 1
        studentsActor ! Increment(expression)

        //updating the score
        presentStudents get studentTerminal.student match {
          case Some(studentRoomState) => {
            val oldScore = studentRoomState.score
            val newScore = oldScore + 10.0

            studentRoomState.score = newScore
            info("Changed score of " + studentTerminal.student + " from " + oldScore + " to " + newScore)

            studentsActor ! ScoreUpdate(studentTerminal.student, oldScore, newScore)
          }
          case None => {
            error("Received increment request from a student that is NOT in the room : " + studentTerminal.student)
          }
        }
      }

      case message: Any => {
        warn("Unhandled message " + message)
      }
    }
  }

  def studentsActor = this.context.actorSelection("../students")

  /**
   * for deferring some behavior.
   */
  def doLater(durationMs: Long)(todo: => Unit) {
    Promise.timeout(None, durationMs, TimeUnit.MILLISECONDS) onSuccess {
      case _ => todo
    }
  }

  /*
   * TODO : move JSON management to students Actor
   */

  //  def reactToStudentMessage(student: Student)(message: JsValue) {
  //    info("Just received from " + student.pseudo + " : " + message)
  //
  //    val messageJsContent = message \ "content"
  //    (message \ "message_type").as[String] match {
  //      case "ask-increment" => {
  //        val expression = findExpressionById((messageJsContent \ "expression_id").as[Long])
  //        self ! TeacherRoomMessage.AskIncrement(student, expression)
  //      }
  //      case _ => ()
  //    }
  //
  //  }
  //
  //  def isIncrementAccepted(expression: Expression, student: Student) = {
  //    import scala.util.Random
  //    
  //    val likelihood = 1.0
  //    //TODO less trivial criteria for incrementing
  //    (Random.nextDouble() < likelihood)
  //  }
  //
  //  sealed abstract class RoomMessageType(val typeName: String)
  //  case object ConnectMessage extends RoomMessageType("connect")
  //  case object DescribeCountersMessage extends RoomMessageType("describe-counters")
  //  case object IncrementMessage extends RoomMessageType("increment")
  //  case object DenyIncrementMessage extends RoomMessageType("deny-increment")
  //
  //  def jsRoomMessageOf(messageType: RoomMessageType, content: JsValue): JsValue = {
  //    return JsObject(Seq(
  //      "message_type" -> JsString(messageType.typeName),
  //      "content" -> content))
  //  }
  //
  //  def sendMessageToStudent(jsMessage: JsValue)(to: Student) {
  //    loggedInStudents.get(to).map(studentData => {
  //      studentData.channel match {
  //        case Some(channel) => {
  //          info("Sending to " + to.pseudo + " message " + jsMessage)
  //          channel.push(jsMessage)
  //        }
  //        case None => {
  //          warn("Could not send message " + jsMessage + " to student " + to.pseudo + " : not connected")
  //        }
  //      }
  //    })
  //  }
  //  
  //  def sendMessageToAll(jsMessage: JsValue){
  //    info("Broadcasting to all students : " + jsMessage)
  //    
  //    val sendMessage = sendMessageToStudent(jsMessage) _
  //    loggedInStudents.keys foreach sendMessage
  //  }
  //  
  //  def newIncrementMessage(expression: Expression): JsValue = {
  //    jsRoomMessageOf(IncrementMessage, Json.obj("expression_id" -> expression.id))
  //  }
  //  
  //  def newDenyIncrementMessage(expression: Expression): JsValue = {
  //    jsRoomMessageOf(DenyIncrementMessage, Json.obj("expression_id" -> expression.id))
  //  }
  
  def logName = "TEACHER-ROOM"
}