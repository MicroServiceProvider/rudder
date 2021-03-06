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
package com.normation.rudder.domain.policies

import net.liftweb.common._
import net.liftweb.util.Helpers.tryo
import com.normation.utils.Control._
import com.normation.rudder.repository.json.JsonExctractorUtils

/**
 * Tags that apply on Rules and Directives
 * We do not warranty unicity of tags name, only on tuple (name, value)
 */

object Tag {
  implicit def tagName(value : String)  : TagName = TagName(value)
  implicit def tagValue(value : String)  : TagValue = TagValue(value)
}

final case class TagName ( val value : String )
final case class TagValue( val value : String )

import Tag._

final case class Tag( name : TagName, value : TagValue )

/**
 * We can have multiple Tags with same name - unicity is really on tuple (name, value)
 */
final case class Tags(tags : Set[Tag]){
  def map[A](f : Tag => A) = {
    tags.map(f)
  }

}

object JsonTagSerialisation {

  import net.liftweb.json._
  import net.liftweb.json.JsonDSL._

  def serializeTags( tags : Tags ) : String = {

    // sort all the tags by name
    val m : JValue = JArray( tags.tags.toList.sortBy ( _.name.value ).map {
       t => ( "key" ->t.name.value) ~ ("value" -> t.value.value ) : JObject
    } )

    compactRender( m )
  }

}

trait JsonTagExtractor[M[_]] extends JsonExctractorUtils[M] {
  import net.liftweb.json._
  import net.liftweb.json.JsonDSL._

  def unserializeTags(value:String): Box[M[Tags]] = {

   parseOpt(value) match {
      case Some(json) => extractTags(json)
      case _ => Failure("Invalid JSON serialization for Tags ${value}")
    }
  }

  def extractTags(value:JValue): Box[M[Tags]] = {
    value match {
      case JArray(jsonTags) =>
        for {
          tags <-
           sequence(jsonTags) {
             jsonTag =>
               for {
                 tagName <- extractJsonString(jsonTag, "key", s => Full(TagName(s)))
                 tagValue <- extractJsonString(jsonTag, "value", s => Full(TagValue(s)))
               } yield {
                 monad.apply2(tagName, tagValue)( (k,v) => Tag(k,v))
               }
           }
        } yield {
          import scalaz.Scalaz.listInstance
          val tagMonad = monad.sequence(tags.toList)
          monad.apply(tagMonad){ t:List[Tag] => Tags(t.toSet)}
        }
      case _ => Failure("Invalid JSON serialization for Tags ${value}")
    }
  }

}
