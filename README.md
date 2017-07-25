# play-auditing

[![Build Status](https://travis-ci.org/hmrc/play-auditing.svg?branch=master)](https://travis-ci.org/hmrc/play-auditing) [ ![Download](https://api.bintray.com/packages/hmrc/releases/play-auditing/images/download.svg) ](https://bintray.com/hmrc/releases/play-auditing/_latestVersion)

play-auditing contains code to facilitate creation of audit events and their publication to datastream. This includes both explicit audit events, and implicit audit events via http-verbs.

## Adding to your build

In your SBT build add:

```scala
resolvers += Resolver.bintrayRepo("hmrc", "releases")

libraryDependencies += "uk.gov.hmrc" %% "play-auditing" % "x.x.x"
```

## Usage

#### Implicit auditing in conjunction with http-verbs

```scala
import uk.gov.hmrc.play.audit.http.config.LoadAuditingConfig
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.audit.http.HttpAuditing
import uk.gov.hmrc.play.config.{AppName, RunMode}
import uk.gov.hmrc.play.http.ws._

object Audit extends AuditConnector with RunMode {
  override lazy val auditingConfig = LoadAuditingConfig(s"$env.auditing")
}

protected object WSHttp extends WSGet with WSPut with WSPost with WSDelete with WSPatch with AppName with RunMode with HttpAuditing {
  override val hooks = Seq(AuditingHook)
  override val auditConnector = Audit
}
```

[For more information on http-verbs, please see the docs here](http://github.com/hmrc/http-verbs)

#### Explicit auditing
```scala
import uk.gov.hmrc.play.audit.http.config.LoadAuditingConfig
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.config.RunMode
import uk.gov.hmrc.play.http.HeaderCarrier
import uk.gov.hmrc.play.audit.AuditExtensions._
import uk.gov.hmrc.play.audit.model.{Audit, DataEvent, EventTypes}

val appName = "preferences"
val audit = Audit(appName,
  new AuditConnector with RunMode {
    override lazy val auditingConfig = LoadAuditingConfig(s"$env.auditing")})

def sendDataEvent(
 transactionName: String, path: String = "N/A", tags: Map[String, String] = Map.empty, detail: Map[String, String])
   (implicit hc: HeaderCarrier): Unit = {

  audit.sendDataEvent(DataEvent(appName, EventTypes.Succeeded,
    tags = hc.toAuditTags(transactionName, path) ++ tags,
    detail = hc.toAuditDetails(detail.toSeq: _*)))

}
```

## Configuration

You'll also need to supply an [auditing configuration](#configuration).

Request auditing is provided for all HTTP requests that are made using http-verbs that use the AuditingHook. Each request/response pair results in an audit message being created and sent to an external auditing service for processing.  To configure this service, your Play configuration file needs to include:

```javascript
auditing {
  enabled = true
  consumer {
    baseUri {
      host = ...
      port = ...
    }
  }
}
```

```HttpAuditing``` provides ```def auditDisabledForPattern: Regex``` which client applications may chose to override when mixing in ```HttpAuditing```.

_NOTE:_ This configuration used to be provided by reading Play configuration property ```<env>.http-client.audit.disabled-for``` which is now obsolete.

## License ##

This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html").

    