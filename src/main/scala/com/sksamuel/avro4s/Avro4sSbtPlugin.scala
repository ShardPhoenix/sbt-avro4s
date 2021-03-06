package com.sksamuel.avro4s

import java.nio.file.{Files, Paths}

import org.apache.avro.Protocol
import org.apache.avro.Schema.{Type => AvroType}
import sbt.Keys._
import sbt._
import plugins._

import collection.JavaConverters._

object Import {

  lazy val avro2Class = taskKey[Seq[File]]("Generate case classes from avro schema files; is a source generator")
  lazy val avroIdl2Avro = taskKey[Seq[File]]("Generate avro schema files from avro IDL; is a resource generator")

  object Avro4sKeys {
    val avroDirectoryName = settingKey[String]("Recurrent directory name used for lookup and output")
    val avroFileEnding = settingKey[String]("File ending of avro schema files, used for lookup and output")
    val avroIdlFileEnding = settingKey[String]("File ending of avro IDL files, used for lookup and output")
    val avroUseTypeRepetition = settingKey[Boolean]("Whether to use type repetition for referenced types in your avro schema files. False means you can reference types by name in the type field, True means type schemas must be fully repeated in places they are referenced")
  }

}

/** @author Stephen Samuel, Timo Merlin Zint */
object Avro4sSbtPlugin extends AutoPlugin {

  override def trigger = allRequirements
  override def requires = JvmPlugin // avoid override of sourceGenerators

  val autoImport = Import

  import autoImport._
  import Avro4sKeys._

  override def projectSettings = Seq(
    avroDirectoryName := "avro",
    avroFileEnding := "avsc",
    avroIdlFileEnding := "avdl",
    avroUseTypeRepetition := false,

    includeFilter in avro2Class := s"*.${avroFileEnding.value}",
    excludeFilter in avro2Class := HiddenFileFilter || FileFilter.globFilter("_*"),
    includeFilter in avroIdl2Avro := s"*.${avroIdlFileEnding.value}",
    excludeFilter in avroIdl2Avro := HiddenFileFilter || FileFilter.globFilter("_*"),

    resourceDirectory in avro2Class := (resourceDirectory in Compile).value / avroDirectoryName.value,
    managedResources in avro2Class := getRecursiveListOfFiles((resourceManaged in avroIdl2Avro).value),
    unmanagedResources in avro2Class := getRecursiveListOfFiles((resourceDirectory in avro2Class).value),

    sourceManaged in avro2Class := (sourceManaged in Compile).value / avroDirectoryName.value,

    resourceDirectory in avroIdl2Avro := (resourceDirectory in Compile).value / avroDirectoryName.value,
    resourceManaged in avroIdl2Avro := (resourceManaged in Compile).value / avroDirectoryName.value,
    resources in avroIdl2Avro := getRecursiveListOfFiles((resourceDirectory in avroIdl2Avro).value),

    managedSourceDirectories in Compile += (sourceManaged in avro2Class).value,
    managedResourceDirectories in Compile += (resourceManaged in avroIdl2Avro).value,

    avro2Class := runAvro2Class.value,
    avroIdl2Avro := runAvroIdl2Avro.value,

    sourceGenerators in Compile += avro2Class.taskValue,
    resourceGenerators in Compile += avroIdl2Avro.taskValue
  )

  private def runAvro2Class: Def.Initialize[Task[Seq[File]]] = Def.task {

    val inc = (includeFilter in avro2Class).value
    val exc = (excludeFilter in avro2Class).value || DirectoryFilter
    val inDir = Seq((resourceDirectory in avro2Class).value, (resourceManaged in avroIdl2Avro).value)
    val outDir = (sourceManaged in avro2Class).value

    streams.value.log.info(s"[sbt-avro4s] Generating sources from [${inDir}]")
    streams.value.log.info("--------------------------------------------------------------")

    val combinedFileFilter = inc -- exc
    val managedFiles = (managedResources in avro2Class).value.filter(combinedFileFilter.accept)
    val unmanagedFiles = (unmanagedResources in avro2Class).value.filter(combinedFileFilter.accept)
    val unmanagedParserType = if (avroUseTypeRepetition.value) ModuleGenerator.multipleParsers _ else ModuleGenerator.singleParser _

    val schemaFiles = managedFiles ++ unmanagedFiles
    streams.value.log.info(s"[sbt-avro4s] Found ${schemaFiles.length} schemas")
    val defs = ModuleGenerator.multipleParsers(managedFiles) ++ unmanagedParserType(unmanagedFiles)
    streams.value.log.info(s"[sbt-avro4s] Generated ${defs.length} classes")

    val paths = FileRenderer.render(outDir.toPath, TemplateGenerator.apply(defs))
    streams.value.log.info(s"[sbt-avro4s] Wrote class files to [${outDir.toPath}]")

    paths.map(_.toFile)
  } dependsOn avroIdl2Avro

  private def runAvroIdl2Avro: Def.Initialize[Task[Seq[File]]] = Def.task {
    import org.apache.avro.compiler.idl.Idl

    val inc = (includeFilter in avroIdl2Avro).value
    val exc = (excludeFilter in avroIdl2Avro).value || DirectoryFilter
    val inDir = (resourceDirectory in avroIdl2Avro).value
    val outDir = (resourceManaged in avroIdl2Avro).value
    val outExt = s".${avroFileEnding.value}"

    streams.value.log.info(s"[sbt-avro4s] Generating sources from [${inDir}]")
    streams.value.log.info("--------------------------------------------------------------")

    val combinedFileFilter = inc -- exc
    val allFiles = (resources in avroIdl2Avro).value
    val idlFiles = Option(allFiles.filter(combinedFileFilter.accept))
    streams.value.log.info(s"[sbt-avro4s] Found ${idlFiles.fold(0)(_.length)} IDLs")

    val schemata = idlFiles.map { f =>
      f.flatMap( file => {
        val idl = new Idl(file.getAbsoluteFile)
        val protocol: Protocol = idl.CompilationUnit()
        val protocolSchemata = protocol.getTypes
        idl.close()
        protocolSchemata.asScala
      }
      ).toSeq
    }.getOrElse(Seq())

    val uniqueSchemata = schemata.groupBy(_.getFullName).mapValues { identicalSchemata =>
      val referenceSchema = identicalSchemata.head
      identicalSchemata.foreach { schema =>
        require(referenceSchema.equals(schema), s"Different schemata with name ${referenceSchema.getFullName} found")
      }
      referenceSchema
    }.values

    streams.value.log.info(s"[sbt-avro4s] Generated ${uniqueSchemata.size} unique schema(-ta)")

    Files.createDirectories(outDir.toPath)
    val schemaFiles = (for (s <- uniqueSchemata if s.getType == AvroType.RECORD) yield {
      val path = Paths.get(outDir.absolutePath, s.getFullName + outExt)
      val writer = Files.newBufferedWriter(path)
      writer.write(s.toString(true))
      writer.close()
      path.toFile
    }).toSeq

    streams.value.log.info(s"[sbt-avro4s] Wrote schema(-ta) to [${outDir.toPath}]")
    schemaFiles
  }

  def getRecursiveListOfFiles(dir: File): Array[File] = {
    val these = dir.listFiles
    if (these == null)
      Array.empty[File]
    else
      these ++ these.filter(_.isDirectory).flatMap(getRecursiveListOfFiles)
  }
}
