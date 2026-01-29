import Dependencies._

ThisBuild / scalaVersion     := "2.12.13"
ThisBuild / version          := "1.0.0-dev"
ThisBuild / organization     := "com.cloud-apim"
ThisBuild / organizationName := "Cloud-APIM"

lazy val excludesSlf4j = Seq(
  ExclusionRule(organization = "org.slf4j"),
)

lazy val root = (project in file("."))
  .settings(
    name := "otoroshi-plugin-mailer",
    assembly / test  := {},
    assembly / assemblyJarName := "otoroshi-plugin-mailer-assembly_2.12-dev.jar",
    libraryDependencies ++= Seq(
      "fr.maif" %% "otoroshi" % "17.12.0" % "provided",
      "javax.mail"   % "javax.mail-api" % "1.6.2",
      "com.sun.mail" % "javax.mail"     % "1.6.2",
      munit % Test
    )
  )