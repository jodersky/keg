package keg

export keg.model.Request
export keg.model.Response
export keg.model.Data
export keg.model.ClientCertificate
export keg.model.UploadFormData
export keg.model.Cookie
export keg.model.WsResponse
export keg.model.Route
export keg.model.Endpoint
export keg.model.EndpointResult
export keg.model.EndpointMeta
export keg.model.EndpointSelector
export keg.model.Recovery
export keg.model.RawResponse
export keg.model.FinalResponse
export keg.model.Ws
export keg.model.WsActor

export keg.main.Server
export keg.main.Secret
export keg.main.Interceptor
export keg.transport.Listener

object default extends keg.derivation.Api
export keg.default.request
export keg.default.get
export keg.default.post
export keg.default.put
export keg.default.delete
export keg.default.patch
export keg.default.websocket
export keg.default.notFound
export keg.default.methodNotAllowed
export keg.default.error

export keg.derivation.routes
export keg.derivation.Result

// export keg.forms.FormResult
export keg.forms.FormData
// export keg.forms.FormPart
// export keg.forms.FromForm

export keg.derivation.abort

def urlEncode(s: String): String =
  java.net.URLEncoder.encode(s, "UTF-8")

def urlDecode(s: String): String =
  java.net.URLDecoder.decode(s, "UTF-8")

export keg.util.url
