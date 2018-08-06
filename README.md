# play-auditing

[![Build Status](https://travis-ci.org/hmrc/play-auditing.svg?branch=master)](https://travis-ci.org/hmrc/play-auditing) [ ![Download](https://api.bintray.com/packages/hmrc/releases/play-auditing/images/download.svg) ](https://bintray.com/hmrc/releases/play-auditing/_latestVersion)

play-auditing contains code to facilitate creation of audit events and their publication to Datastream. This includes both explicit events and implicit events.

Explicit events are where your code creates an audit event and asks for it to be sent. Implicit events are created automatically by via http-core when a service makes some HTTP request, and is based on configuration.

## Adding to your build

In your SBT build add:

```scala
resolvers += Resolver.bintrayRepo("hmrc", "releases")

libraryDependencies += "uk.gov.hmrc" %% "play-auditing" % "x.x.x"
```

## Usage

#### Implicit auditing of outgoing HTTP calls in conjunction with http-verbs and Play

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
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.audit.http.config.AuditingConfig
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.audit.AuditExtensions._
import uk.gov.hmrc.play.audit.model.DataEvent
import scala.concurrent.ExecutionContext.Implicits.global

// simplest default config (you will want to do something different)
val config = AuditingConfig(consumer = None, enabled = true)

// setup global objects
val appName = "preferences"
val connector = AuditConnector(config)

// get objects relating to the current request
val carrier = HeaderCarrier()

// create the audit event
val event = DataEvent(appName, "SomeEventHappened",
  tags = carrier.toAuditTags(
    transactionName = "some_series_of_events",
    path = "/some/path"
  ),
  detail = carrier.toAuditDetails(
    "someServiceSpecificKey" -> "someValue"
  ))

connector.sendEvent(event)
```

## Configuration

You'll also need to supply an [auditing configuration](#configuration).

Request auditing is provided for all HTTP requests that are made using http-core that use the AuditingHook. Each request/response pair results in an audit message being created and sent to an external auditing service for processing.  To use this service, your configuration needs to include:

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

_NOTE:_ The ```traceRequests``` property is not used, so can be removed. The configuration remains backwards compatible, so will not fail if present.

```HttpAuditing``` provides ```def auditDisabledForPattern: Regex``` which client applications may chose to override when mixing in ```HttpAuditing```.

_NOTE:_ This configuration used to be provided by reading Play configuration property ```<env>.http-client.audit.disabled-for``` which is now obsolete.

## License ##

This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html").
    
