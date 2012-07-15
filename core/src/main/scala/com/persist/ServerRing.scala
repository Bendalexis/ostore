/*
 *  Copyright 2012 Persist Software
 *  
 *   http://www.persist.com
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
*/

package com.persist

import akka.actor.ActorSystem
import akka.actor.Props
import akka.actor.ActorRef
import JsonOps._
import akka.pattern._
import akka.util.Timeout
import akka.util.duration._
import akka.dispatch.Await
import scala.collection.immutable.TreeMap

private[persist] class NodeInfo(val name: String, val node: ActorRef)

private[persist] class ServerRing(databaseName:String, ringName:String, send:ActorRef, var config:DatabaseConfig, serverConfig: Json, create: Boolean) extends CheckedActor {
  val serverName = jgetString(serverConfig, "host") + ":" + jgetInt(serverConfig, "port")
  var nodes = TreeMap[String, NodeInfo]()
  implicit val timeout = Timeout(5 seconds)

  def newNode(ringName: String, nodeName: String) {
    val node = context.actorOf(Props(new ServerNode(databaseName, ringName, nodeName, send, config, serverConfig, create)), name = nodeName)
    val f = node ? ("start1")
    Await.result(f,5 seconds)
    val info = new NodeInfo(nodeName, node)
    nodes += (nodeName -> info)
  }

  // TODO do in parallel
  for ((ringName,ringConfig)<-config.rings) {
    for ((nodeName,nodeConfig)<-ringConfig.nodes) {
      if (nodeConfig.server.name == serverName) {
        newNode(ringName, nodeName)
      }
    }
  }

  def rec = {
    case ("start1") => {
      sender !  Codes.Ok 
    }
    case ("start2") => {
      for ((nodeName, nodeInfo) <- nodes) {
        val f = nodeInfo.node ? ("start2")
        val v = Await.result(f, 5 seconds)
      }
      sender !  Codes.Ok 
    }
    case ("stop1") => {
      for ((nodeName, nodeInfo) <- nodes) {
        val f = nodeInfo.node ? ("stop1")
        val v = Await.result(f, 5 seconds)
      }
      sender ! Codes.Ok
    }
    case ("stop2") => {
      for ((nodeName, nodeInfo) <- nodes) {
        val f = nodeInfo.node ? ("stop2")
        val v = Await.result(f, 5 seconds)
      }
      val f = send ? ("stop")
      Await.result(f, 5 seconds)
      sender ! Codes.Ok
    }
    case ("stopBalance") => {
      for ((nodeName, nodeInfo) <- nodes) {
        val f = nodeInfo.node ? ("stopBalance")
        val v = Await.result(f, 5 seconds)
      }
      sender !  Codes.Ok 
    }
    case ("startBalance") => {
      for ((nodeName, nodeInfo) <- nodes) {
        val f = nodeInfo.node ? ("startBalance")
        val v = Await.result(f, 5 seconds)
      }
      sender !  Codes.Ok 
    }
    case ("busyBalance") => {
      var code = Codes.Ok
      for ((nodeName, nodeInfo) <- nodes) {
        val f = nodeInfo.node ? ("busyBalance")
        val code1 = Await.result(f, 5 seconds)
        if (code1 == Codes.Busy) code = Codes.Busy
      }
      sender !  code
    }
    case ("addNode", ringName:String, nodeName:String, config:DatabaseConfig) => {
      this.config = config
      if (ringName == this.ringName) {
        newNode(ringName, nodeName)
      }
      for ((nodeName, nodeInfo) <- nodes) {
        val f = nodeInfo.node ? ("setConfig", config)
        val v = Await.result(f, 5 seconds)
      }
      sender !  Codes.Ok 
    }
    case ("getLowHigh", nodeName:String, tableName:String) => {
      val nodeInfo = nodes(nodeName)
      val f = nodeInfo.node ? ("getLowHigh", tableName)
      val (code:String, result:Json) = Await.result(f, 5 seconds)
      sender !  (Codes.Ok,result) 
    }
    case ("setLowHigh", nodeName:String, tableName:String, low:String, high:String) => {
      val nodeInfo = nodes(nodeName)
      val f = nodeInfo.node ? ("setLowHigh", tableName, low, high)
      Await.result(f, 5 seconds)
      sender !  Codes.Ok
    }
    case ("addTable1", tableName:String, config:DatabaseConfig) => {
      for ((nodeName, nodeInfo) <- nodes) {
        val f = nodeInfo.node ? ("addTable1", tableName, config)
        val v = Await.result(f, 5 seconds)
      }
      this.config = config
      sender !  Codes.Ok 
    }
    case ("addTable2", tableName:String) => {
      for ((nodeName, nodeInfo) <- nodes) {
        val f = nodeInfo.node ? ("addTable2", tableName)
        val v = Await.result(f, 5 seconds)
      }
      sender !  Codes.Ok 
    }
    case ("deleteTable1", tableName:String, config:DatabaseConfig) => {
      for ((nodeName, nodeInfo) <- nodes) {
        val f = nodeInfo.node ? ("deleteTable1", tableName, config)
        val v = Await.result(f, 5 seconds)
      }
      this.config = config
      sender !  Codes.Ok 
    }
    case ("deleteTable2", tableName:String) => {
      for ((nodeName, nodeInfo) <- nodes) {
        val f = nodeInfo.node ? ("deleteTable2", tableName)
        val v = Await.result(f, 5 seconds)
      }
      sender !  Codes.Ok 
    }
  }

}