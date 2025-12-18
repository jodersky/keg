package keg.model

class WsResponse(
    val f: castor.Context ?=> castor.Actor[Ws.Event] => castor.Actor[Ws.Event]
)
