package com.github.agourlay.cornichon.core

import com.github.agourlay.cornichon.core.SessionSpec.{ badKeyGen, keyGen, valueGen }
import org.scalacheck.Properties
import org.scalacheck.Prop._
import org.typelevel.claimant.Claim

class SessionProperties extends Properties("Session") {

  property("addValue error if key contains illegal chars") =
    forAll(badKeyGen, valueGen) { (keyWithForbiddenChar, value) ⇒
      Claim {
        Session.newEmpty.addValue(keyWithForbiddenChar, value) == Left(IllegalKey(keyWithForbiddenChar))
      }
    }

  property("addValue error not accept empty key") =
    forAll(valueGen) { (value) ⇒
      Claim {
        Session.newEmpty.addValue("", value) == Left(EmptyKey)
      }
    }

  property("addValue write the value") =
    forAll(keyGen, valueGen) { (key, value) ⇒
      val s2 = Session.newEmpty.addValueUnsafe(key, value)
      Claim {
        s2.content.get(key).contains(Vector(value))
      }
    }

  property("addValues error if one key is empty") = {
    forAll(valueGen, valueGen) { (v1, v2) ⇒
      Claim {
        Session.newEmpty.addValues("a" -> v1, "" -> v2) == (Left(EmptyKey))
      }
    }
  }

  property("addValues throw if key contains illegal chars") = {
    forAll(keyGen, badKeyGen, valueGen, valueGen) { (key, badKey, v1, v2) ⇒
      Claim {
        Session.newEmpty.addValues(key -> v1, badKey -> v2) == Left(IllegalKey(badKey))
      }
    }
  }

  property("addValues write the values") = {
    forAll(keyGen, valueGen, keyGen, valueGen) { (k1, v1, k2, v2) ⇒
      val s2 = Session.newEmpty.addValuesUnsafe(k1 -> v1, k2 -> v2)
      Claim {
        if (k1 == k2)
          s2.content.get(k1).contains(Vector(v1, v2))
        else {
          s2.content.get(k1).contains(Vector(v1))
          s2.content.get(k2).contains(Vector(v2))
        }
      }
    }
  }

  property("get returns a written value") = {
    forAll(keyGen, valueGen) { (key, value) ⇒
      val s2 = Session.newEmpty.addValueUnsafe(key, value)
      Claim {
        s2.get(key) == Right(value)
      }
    }
  }

  property("get returns an error if the key does not exist") = {
    forAll(keyGen) { key ⇒
      val s = Session.newEmpty
      Claim {
        s.get(key) == Left(KeyNotFoundInSession(key, s))
      }
    }
  }

  property("get take the last value in session without index param") =
    forAll(keyGen, valueGen, valueGen) { (key, firstValue, secondValue) ⇒
      val s = Session.newEmpty.addValueUnsafe(key, firstValue)
      Claim {
        s.addValueUnsafe(key, secondValue).get(key) == Right(secondValue)
      }
    }

  property("get take the first value in session with index = zero") =
    forAll(keyGen, valueGen, valueGen) { (key, firstValue, secondValue) ⇒
      val s = Session.newEmpty.addValueUnsafe(key, firstValue)
      Claim {
        s.addValueUnsafe(key, secondValue).get(key, Some(0)) == Right(firstValue)
      }
    }

  property("get take the second value in session with index = 1") =
    forAll(keyGen, valueGen, valueGen) { (key, firstValue, secondValue) ⇒
      val s = Session.newEmpty.addValueUnsafe(key, firstValue)
      Claim {
        s.addValueUnsafe(key, secondValue).get(key, Some(1)) == Right(secondValue)
      }
    }

  property("get returns an error if the key exists but not the index") = {
    forAll(keyGen, valueGen, valueGen) { (key, firstValue, secondValue) ⇒
      val s = Session.newEmpty.addValueUnsafe(key, firstValue).addValueUnsafe(key, secondValue)
      val error = IndexNotFoundForKey(key, 3, Vector(firstValue, secondValue))
      Claim {
        s.get(key, Some(3)) == Left(error)
        error.renderedMessage == (s"index '3' not found for key '$key' with values \n0 -> $firstValue\n1 -> $secondValue")
      }
    }
  }

  property("getOps return None if key does not exist") = {
    forAll(keyGen) { key ⇒
      Claim {
        Session.newEmpty.getOpt(key).isEmpty
      }
    }
  }

  property("getOps return a written value") =
    forAll(keyGen, valueGen) { (key, value) ⇒
      val s2 = Session.newEmpty.addValueUnsafe(key, value)
      Claim {
        s2.getOpt(key).contains(value)
      }
    }

  property("rollbackKey rollback properly") =
    forAll(keyGen, valueGen, valueGen) { (key, value1, value2) ⇒
      val s = Session.newEmpty.addValueUnsafe(key, value1).addValueUnsafe(key, value2)
      Claim {
        s.get(key) == Right(value2)
        s.rollbackKey(key).flatMap(_.get(key)) == Right(value1)
      }
    }

  property("rollbackKey delete key if it has only one value") = {
    forAll(keyGen, valueGen) { (key, value) ⇒
      val s = Session.newEmpty.addValueUnsafe(key, value)
      Claim {
        s.get(key) == Right(value)
        s.rollbackKey(key).map(_.getOpt(key)) == Right(None)
      }
    }
  }

  property("getPrevious return None if the key has only one value") = {
    forAll(keyGen, valueGen) { (key, firstValue) ⇒
      val s = Session.newEmpty.addValueUnsafe(key, firstValue)
      Claim {
        s.getPrevious(key) == Right(None)
      }
    }
  }

  property("getList error if one of the key does not exist") = {
    forAll(keyGen, keyGen, keyGen, valueGen, valueGen) { (firstKey, secondKey, thirdKey, firstValue, secondValue) ⇒
      val s2 = Session
        .newEmpty
        .addValueUnsafe(firstKey, firstValue)
        .addValueUnsafe(secondKey, secondValue)
      Claim {
        s2.getList(Seq(firstKey, thirdKey)).isLeft
      }
    }
  }

  property("getPrevious return an Option of the previous value in session") = {
    forAll(keyGen, valueGen, valueGen) { (key, firstValue, secondValue) ⇒
      val s = Session.newEmpty.addValueUnsafe(key, firstValue).addValueUnsafe(key, secondValue)
      Claim {
        s.getPrevious(key) == Right(Some(firstValue))
      }
    }
  }

  property("removeKey remove entry") = {
    forAll(keyGen, valueGen) { (key, value) ⇒
      val s = Session.newEmpty.addValueUnsafe(key, value)
      Claim {
        s.get(key) == Right(value)
        s.removeKey(key).getOpt(key).isEmpty
      }
    }
  }

  property("removeKey not throw error if key does not exist") = {
    forAll(keyGen) { key ⇒
      val s = Session.newEmpty.removeKey(key)
      Claim {
        s.removeKey(key).getOpt(key).isEmpty
      }
    }
  }

}
