package com.github.ekiaa.transactional_actor

import akka.persistence.{PersistentActor, RecoveryCompleted, SnapshotOffer}

import scala.concurrent.Future
import scala.util.Try

abstract class TransactionalActor extends PersistentActor {

  import context.dispatcher

  private var persistedState: Option[PersistedState] = None

  private var transientState: Option[TransientState] = None

  private var operationState: Option[OperationState] = None

  def emptyPersistedState: PersistedState

  def onSnapshotRecovery(snapshot: Any): PersistedState

  def onRecoveryCompleted(persistedState: PersistedState): TransientAction

  override def receiveRecover: Receive = {
    case SnapshotOffer(_, snapshot) =>
      persistedState = Some(onSnapshotRecovery(snapshot))
    case event: Event =>
      persistedState = Some(persistedState.getOrElse(emptyPersistedState).applyEvent(event))
    case RecoveryCompleted =>
      transientState = Some(
        handleTransientAction(
          onRecoveryCompleted(
            persistedState.getOrElse(emptyPersistedState)
          )
        )
      )
      persistedState = None
      context.become(receiveTransient)
  }

  def onTransientStateCompleted(transientState: TransientState): OperationState

  private def receiveTransient: Receive = {
    case TransientActionResult(result) =>
      transientState = Some(
        handleTransientAction(
          transientState.get
            .handleTransientActionResult(result)
        )
      )
    case TransientStateCompleted =>
      operationState = Some(
        onTransientStateCompleted(
          transientState.get
        )
      )
      unstashAll()
      context.become(receiveCommand)
    case _ =>
      stash()
  }

  private def handleTransientAction(transientAction: TransientAction): TransientState = {
    transientAction match {
      case AwaitResult(future, newTransientState) =>
        future.onComplete(result => context.self ! TransientActionResult(result))
        newTransientState
      case Complete(newTransientState) =>
        context.self ! TransientStateCompleted
        newTransientState
    }
  }

  override def receiveCommand: Receive = {
    case message =>
      operationState.get
        .handleMessage(message)
  }

  private def handleOperationAction(operationAction: OperationAction): OperationState = {

  }

}


trait Action

trait Event

trait PersistedState {
  def applyEvent(event: Event): PersistedState
}


trait TransientMessage

trait TransientAction extends Action
case class AwaitResult(future: Future[TransientMessage], transientState: TransientState) extends TransientAction
case class TransientActionResult(result: Try[TransientMessage])
case class Complete(transientState: TransientState) extends TransientAction
case object TransientStateCompleted

trait TransientState {
  def handleTransientActionResult(result: Try[TransientMessage]): TransientAction
}


trait OperationAction extends Action

trait OperationState {
  def handleMessage(message: Any): OperationAction
}

