package com.github.agourlay.cornichon.sbtinterface

import sbt.testing._

class CornichonFramework extends Framework {

  override def fingerprints(): Array[Fingerprint] = Array(CornichonFingerprint)

  def runner(args: Array[String], remoteArgs: Array[String], testClassLoader: ClassLoader): Runner =
    new CornichonRunner(args, remoteArgs, testClassLoader)

  override def name(): String = "cornichon"

}