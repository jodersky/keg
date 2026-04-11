package keg.derivation

inline def routes(): Seq[keg.model.Route] = ${keg.derivation.routesImpl()}
