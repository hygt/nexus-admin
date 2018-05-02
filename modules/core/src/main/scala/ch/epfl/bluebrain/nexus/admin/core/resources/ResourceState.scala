package ch.epfl.bluebrain.nexus.admin.core.resources

import ch.epfl.bluebrain.nexus.admin.core.resources.ResourceCommand._
import ch.epfl.bluebrain.nexus.admin.core.resources.ResourceEvent.{ResourceCreated, ResourceDeprecated, ResourceUpdated}
import ch.epfl.bluebrain.nexus.admin.core.resources.ResourceRejection._
import ch.epfl.bluebrain.nexus.admin.core.types.Versioned
import ch.epfl.bluebrain.nexus.admin.refined.ld.Id
import ch.epfl.bluebrain.nexus.commons.types.Meta
import io.circe.Json

/**
  * Enumeration type for possible states of a resource.
  */
sealed trait ResourceState extends Product with Serializable

object ResourceState {

  /**
    * Initial state for all resources.
    */
  final case object Initial extends ResourceState

  /**
    * State used for all resources that have been created and later possibly updated or deprecated.
    *
    * @param id         the identifier of the resource
    * @param rev        the selected revision number
    * @param meta       the metadata associated to this resource
    * @param value      the json payload of the resource
    * @param deprecated the deprecation status
    */
  final case class Current(id: Id, rev: Long, meta: Meta, value: Json, deprecated: Boolean)
      extends ResourceState
      with Versioned

  /**
    * State transition function for resources; considering a current state (the ''state'' argument) and an emitted
    * ''event'' it computes the next state.
    *
    * @param state the current state
    * @param event the emitted event
    * @return the next state
    */
  def next(state: ResourceState, event: ResourceEvent): ResourceState = {

    (state, event: ResourceEvent) match {
      case (Initial, ResourceCreated(id, rev, meta, _, value)) =>
        Current(id, rev, meta, value, deprecated = false)
      // $COVERAGE-OFF$
      case (Initial, _) => Initial
      // $COVERAGE-ON$
      case (c @ Current(_, _, _, _, true), _) => c
      case (c, _: ResourceCreated)            => c
      case (c: Current, ResourceUpdated(_, rev, meta, _, value)) =>
        c.copy(rev = rev, meta = meta, value = value)
      case (c: Current, ResourceDeprecated(_, rev, meta, _)) =>
        c.copy(rev = rev, meta = meta, deprecated = true)
    }
  }

  private[core] class Eval {

    def createResource(state: ResourceState, c: CreateResource): Either[ResourceRejection, ResourceEvent] =
      state match {
        case Initial => Right(ResourceCreated(c.id, 1L, c.meta, c.tags, c.value))
        case _       => Left(ResourceAlreadyExists)
      }

    def updateResource(state: ResourceState, c: UpdateResource): Either[ResourceRejection, ResourceEvent] =
      state match {
        case Initial                                  => Left(ResourceDoesNotExists)
        case Current(_, rev, _, _, _) if rev != c.rev => Left(IncorrectRevisionProvided)
        case Current(_, _, _, _, true)                => Left(ResourceIsDeprecated)
        case s: Current                               => updateResourceAfter(s, c)
      }

    def updateResourceAfter(state: Current, c: UpdateResource): Either[ResourceRejection, ResourceEvent] =
      Right(ResourceUpdated(state.id, state.rev + 1, c.meta, c.tags, c.value))

    def deprecateResource(state: ResourceState, c: DeprecateResource): Either[ResourceRejection, ResourceEvent] =
      state match {
        case Initial                                  => Left(ResourceDoesNotExists)
        case Current(_, rev, _, _, _) if rev != c.rev => Left(IncorrectRevisionProvided)
        case Current(_, _, _, _, true)                => Left(ResourceIsDeprecated)
        case s: Current                               => deprecateResourceAfter(s, c)
      }

    def deprecateResourceAfter(state: Current, c: DeprecateResource): Either[ResourceRejection, ResourceEvent] =
      Right(ResourceDeprecated(state.id, state.rev + 1, c.meta, c.tags))

    /**
      * Command evaluation logic for resources; considering a current ''state'' and a command to be evaluated either
      * reject the command or emit a new event that characterizes the change for an aggregate.
      *
      * @param state the current state
      * @param cmd   the command to be evaluated
      * @return either a rejection or emit an event
      */
    final def apply(state: ResourceState, cmd: ResourceCommand): Either[ResourceRejection, ResourceEvent] = {

      cmd match {
        case c: CreateResource    => createResource(state, c)
        case c: UpdateResource    => updateResource(state, c)
        case c: DeprecateResource => deprecateResource(state, c)
      }
    }
  }
}
