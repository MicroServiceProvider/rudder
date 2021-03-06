/*
*************************************************************************************
* Copyright 2016 Normation SAS
*************************************************************************************
*
* This file is part of Rudder.
*
* Rudder is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU General Public License version 3, the copyright holders add
* the following Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
* Public License version 3, when you create a Related Module, this
* Related Module is not considered as a part of the work and may be
* distributed under the license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* Rudder is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

*
*************************************************************************************
*/

package com.normation.rudder.hooks

import java.io.File

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal

import net.liftweb.common.Box
import net.liftweb.common.EmptyBox
import net.liftweb.common.Failure
import net.liftweb.common.Full

import org.slf4j.LoggerFactory
import net.liftweb.common.Logger
import net.liftweb.util.Helpers.tryo
import scala.io.Source

/*
 * The goal of that file is to give a simple abstraction to run hooks in
 * rudder.
 *
 * Hooks are stored in a directory. All hooks from a directory are
 * run sequentially, so that side effects from one hook can be used
 * in the following if the user want so.
 * A hook which fails stop the process and error from stderr are used
 * for the reason of the failing.
 * A failed hook is decided by the return code: 0 mean success, anything
 * else is a failure.
 *
 * Hooks are asynchronously executed by default, in a Future.
 */

/*
 * Hooks are group in "set". We run all the hooks
 * from the same set with the same set of envVariables.
 * The hooks are executed in the order of the list.
 */

final case class Hooks(basePath: String, hooksFile: List[String])
/**
 * Hook env are pairs of environment variable name <=> value
 */
final case class HookEnvPair(name: String, value: String) {
  def show = s"[${name}:${value}]"
}
final case class HookEnvPairs(values: List[HookEnvPair]) {
  //shortcut to view envVariables as a Map[String, String]
  def toMap = values.map(p => (p.name, p.value)).toMap

  def add(other: HookEnvPairs) = HookEnvPairs(this.values ::: other.values)

  /**
   * Formatted string
   * [key1:val1][key2:val2]...
   */
  def show: String = values.map(_.show).mkString(" ")
}

object HookEnvPairs {
  def toListPairs(values: (String, String)*) = values.map( p => HookEnvPair(p._1, p._2)).toList

  def build( values: (String, String)*) = {
    HookEnvPairs(toListPairs(values:_*))
  }
}

/**
 * Loggger for hooks
 */
object HooksLogger extends Logger {
  override protected def _logger = LoggerFactory.getLogger("hooks")
}

object RunHooks {

  /**
   * Runs a list of hooks. Each hook is run sequencially (so that
   * the user can expects one hook side effects to be used in the
   * next one), but the whole process is asynchronous.
   * If one hook fails, the whole list fails.
   *
   * The semantic of return codes is:
   * - < 0: success (we should never have a negative returned code, but java int are signed)
   * - 0: success
   * - 1-31: errors. These code stop the hooks pipeline, and the generation is on error
   * - 32-63: warnings. These code log a warning message, but DON'T STOP the next hook processing
   * - 64-255: reserved. For now, they will be treat as "error", but that behaviour can change any-time
   *            without notice.
   * - > 255: should not happen, but treated as reserved.
   *
   */
  def asyncRun(hooks: Hooks, hookParameters: HookEnvPairs, envVariables: HookEnvPairs): Future[Box[Unit]] = {
    /*
     * We can not use Future.fold, because it execute all scripts
     * in parallele and then combine their results. Our semantic
     * is execute script one after the other, combining at each
     * step.
     * But we still want the whole operation to be non-bloking.
     */
    ( Future(Full(()):Box[Unit]) /: hooks.hooksFile) { case (previousFuture, nextHookName) =>
      val path = hooks.basePath + File.separator + nextHookName
      previousFuture.flatMap {
        case Full(())     =>
          HooksLogger.debug(s"Run hook: '${path}' with environment parameters: ${hookParameters.show}")
          HooksLogger.trace(s"System environment variables: ${envVariables.show}")
          val env = envVariables.add(hookParameters)
          RunNuCommand.run(Cmd(path, Nil, env.toMap)).map { result =>
            lazy val msg = s"Exit code=${result.code} for hook: '${path}' with environment variables: ${env.show}. \n  Stdout: '${result.stdout}' \n  Stderr: '${result.stderr}'"
            HooksLogger.trace(s"  -> results: ${msg}")
            if(       result.code <= 0 ) {
              Full(())
            } else if(result.code >= 1  && result.code <= 31 ) { // error
              Failure(msg)
            } else if(result.code >= 32 && result.code <= 64) { // warning
              HooksLogger.warn(msg)
              Full(())
            } else { //reserved - like error for now
              Failure(msg)
            }
          } recover {
            case ex: Exception => Failure(s"Exception when executing '${path}' with environment variables: ${env.show}: ${ex.getMessage}")
          }
        case eb: EmptyBox => Future(eb)
      }
    }
  }

  /*
   * Run hooks in given directory, synchronously.
   *
   * Only the files with prefix ".hook" are selected as hooks, all
   * other files will be ignored.
   *
   * The hooks will be run in lexigraphically order, so that the
   * "standard" ordering of unix hooks (or init.d) with numbers
   * works as expected:
   *
   * 01-first.hook
   * 20-second.hook
   * 30-third.hook
   * etc
   *
   *
   */
  def syncRun(hooks: Hooks, hookParameters: HookEnvPairs, envVariables: HookEnvPairs): Box[Unit] = {
    try {
      //cmdInfo is just for comments/log. We use "*" to synthetize
      val cmdInfo = s"'${hooks.basePath}' with environment parameters: ${hookParameters.show}"
      HooksLogger.debug(s"Run hooks: ${cmdInfo}")
      HooksLogger.trace(s"Hook environment variables: ${envVariables.show}")
      val time_0 = System.currentTimeMillis
      val res = Await.result(asyncRun(hooks, hookParameters, envVariables), Duration.Inf)
      HooksLogger.debug(s"Done in ${System.currentTimeMillis - time_0} ms: ${cmdInfo}")
      res
    } catch {
      case NonFatal(ex) => Failure(s"Error when executing hooks in directory '${hooks.basePath}'. Error message is: ${ex.getMessage}")
    }
  }

  /**
   * Get the hooks set for the given directory path.
   * Hooks must be executable and not ends with one of the
   * non-executable extensions.
   */
   def getHooks(basePath: String, ignoreSuffixes: List[String]): Box[Hooks] = {
     tryo {
       val dir = new File(basePath)
       // Check that dir exists before looking in it
       if (dir.exists) {
         // only keep executable files
         val files = dir.listFiles().toList.flatMap { file =>
           file match {
             case f if (f.isDirectory) => None
             case f =>
               if(f.canExecute) {
                 val name = f.getName
                 //compare ignore case (that's why it's a regienMatches) extension and name
                 ignoreSuffixes.find(suffix => name.regionMatches(true, name.length - suffix.length, suffix, 0, suffix.length)) match {
                   case Some(suffix) =>
                     HooksLogger.debug(s"Ignoring hook '${f.getAbsolutePath}' because suffix '${suffix}' is in the ignore list")
                     None
                   case None      =>
                     Some(f.getName)
                 }
               } else {
                 HooksLogger.debug(s"Ignoring hook '${f.getAbsolutePath}' because it is not executable. Check permission if not expected behavior.")
                 None
               }
           }
         }.sorted // sort them alphanumericaly
         Hooks(basePath, files)
       } else {
         HooksLogger.debug(s"Ignoring hook directory '${dir.getAbsolutePath}' because path does not exists")
         // return an empty Hook
         Hooks(basePath, List[String]())
       }
     }
   }

}
