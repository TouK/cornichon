package com.github.agourlay.cornichon.matchers

import java.time.Instant
import java.time.format.DateTimeFormatter

import com.github.agourlay.cornichon.matchers.Matchers._
import io.circe.Json
import org.scalacheck._
import org.scalacheck.Prop._
import org.typelevel.claimant.Claim

object MatchersProperties extends Properties("Matchers") {

  val reasonablyRandomInstantGen: Gen[Instant] = for {
    randomOffset ← Arbitrary.arbLong.arbitrary
  } yield Instant.now().plusMillis(randomOffset % 1000000000000L)

  val instantGen: Gen[Instant] = for {
    randomOffset ← Arbitrary.arbLong.arbitrary
  } yield Instant.now().plusMillis(randomOffset)

  property("any-integer correct for any int") =
    forAll(Gen.size) { int ⇒
      Claim {
        anyInteger.predicate(Json.fromInt(int))
      }
    }

  property("any-integer incorrect for any alphanum string") =
    forAll(Gen.alphaNumStr) { alphanum ⇒
      Claim {
        !anyInteger.predicate(Json.fromString(alphanum))
      }
    }

  property("any-positive-integer correct for any positive int") =
    forAll(Gen.posNum[Int]) { int ⇒
      Claim {
        anyPositiveInteger.predicate(Json.fromInt(int))
      }
    }

  property("any-positive-integer incorrect for any alphanum string") =
    forAll(Gen.alphaNumStr) { alphanum ⇒
      Claim {
        !anyPositiveInteger.predicate(Json.fromString(alphanum))
      }
    }

  property("any-negative-integer correct for any negative int") =
    forAll(Gen.negNum[Int]) { int ⇒
      Claim {
        anyNegativeInteger.predicate(Json.fromInt(int))
      }
    }

  property("any-negative-integer incorrect for any alphanum string") =
    forAll(Gen.alphaNumStr) { alphanum ⇒
      Claim {
        !anyNegativeInteger.predicate(Json.fromString(alphanum))
      }
    }

  property("any-uuid correct for any valid UUID") =
    forAll(Gen.uuid) { uuid ⇒
      Claim {
        anyUUID.predicate(Json.fromString(uuid.toString))
      }
    }

  property("any-uuid incorrect for any alphanum string") =
    forAll(Gen.alphaNumStr) { alphanum ⇒
      Claim {
        !anyUUID.predicate(Json.fromString(alphanum))
      }
    }

  property("any-date-time correct for all ISO-compliant values, including Y10K+ dates") =
    forAll(instantGen) { instant ⇒
      Claim {
        anyDateTime.predicate(Json.fromString(DateTimeFormatter.ISO_INSTANT.format(instant)))
      }
    }
}