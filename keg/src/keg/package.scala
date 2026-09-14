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
export keg.model.EndpointMeta
export keg.model.Recovery
export keg.model.RawResponse
export keg.model.Static
export keg.model.Ws
export keg.model.WsActor

export keg.main.Server
export keg.main.Secret
export keg.main.Interceptor
export keg.transport.Listener

object default extends keg.derivation.Api
export default.request
export default.get
export default.post
export default.put
export default.delete
export default.patch
export default.error
export default.FromPath
export default.FromRequest
export default.ToResponse

export keg.derivation.routes
export keg.derivation.Result
export keg.derivation.Api

// export keg.forms.FormResult
export keg.forms.FormData
// export keg.forms.FormPart
// export keg.forms.FromForm

export keg.main.AbortException
export keg.main.abort

def urlEncode(s: String): String =
  java.net.URLEncoder.encode(s, "UTF-8")

def urlDecode(s: String): String =
  java.net.URLDecoder.decode(s, "UTF-8")

export keg.util.url
