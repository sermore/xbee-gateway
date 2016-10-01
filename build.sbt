name := "Xbee Gateway"
version := "0.1"
scalaVersion := "2.11.8"
fork := true
scalacOptions ++= Seq("-feature", "-deprecation")
javaOptions += "-Djava.library.path=/usr/lib/jni"
javaOptions in Universal ++= Seq("-Djava.library.path=/usr/lib/jni", "-Dconfig.file=conf/application.conf", "conf/lab.conf")

resolvers += "Local Maven Repository" at "file://"+Path.userHome.absolutePath+"/.m2/repository"
//resolvers += "Artima Maven Repository" at "http://repo.artima.com/releases"
enablePlugins(JavaAppPackaging)
libraryDependencies ++= Seq(
  "org.scalatest" %% "scalatest" % "3.0.0" % "test",
  "com.typesafe.akka" %% "akka-actor" % "2.4.9",
  "com.typesafe.akka" %% "akka-http-core" % "2.4.9",
  "com.typesafe.akka" %% "akka-http-testkit" % "2.4.9" % Test,
  "com.typesafe.akka" %% "akka-http-spray-json-experimental" % "2.4.9",
  "com.typesafe.akka" %% "akka-agent" % "2.4.9",
  "com.typesafe.akka" %% "akka-slf4j" % "2.4.9",  
  "com.digi.xbee" % "xbjlib" % "1.1.1" exclude("org.rxtx", "rxtx") exclude("org.rxtx", "rxtx-native") exclude("org.slf4j", "slf4j-jdk14"),
  "org.rxtx" % "rxtx" % "2.1.7",
  "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.8.0.rc2",
  "com.typesafe.scala-logging" %% "scala-logging" % "3.4.0",
//  "org.slf4j" % "slf4j-log4j12" % "1.7.21"
  "ch.qos.logback" % "logback-classic" % "1.1.3"
)