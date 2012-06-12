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

import JsonOps._
import akka.actor.ActorSystem
import akka.actor.Props
import com.typesafe.config.ConfigFactory
import akka.actor.ActorRef
import akka.pattern._
import akka.util.Timeout
import akka.util.duration._
import akka.dispatch._
import akka.actor.Actor
import akka.remote.RemoteClientError
import akka.remote.RemoteClientWriteFailed
import akka.actor.DeadLetter
import akka.remote.RemoteLifeCycleEvent
import scala.collection.immutable.TreeMap
import java.io.File
import scala.io.Source

// TODO 1. remove most await's
// TODO 2. remove uneeded import and sort
// TODO 4. remove most vars
// TODO 5. use scala rather than java libs where possible
// TODO 6. get rid of most returns
// TODO 7. add logging
// TODO 8. break out client as sep project (jsonops, send,synclient,asyncclient)
// TODO 9. time skew of nodes in ring, time always increments
// TODO 10. fix up timeouts (special debug settings)


class Listener extends Actor {
  def receive = {
    case r: RemoteClientError => {
      println("*****Remote Client Error:" + r.getCause().getMessage() + ":" + r.getRemoteAddress())
    }
    case w: RemoteClientWriteFailed => {
      println("*****Remote Write Failed:" + w.getRequest() + ":" + w.getRemoteAddress())
    }
    case d: DeadLetter => {
      println("*****DeadLetter:" + d.recipient.path + ":" + d.message)
    }
    case x => println("*****Other Event:" + x)
  }
}

class DatabaseInfo(
  val system: ActorSystem, 
  val serverName: String, 
  val databaseName: String, 
  var map: NetworkMap, 
  var config: DatabaseConfig,
  var state: String) {
  private lazy val serverInfo = map.serverInfo(serverName)
  def client(databaseName: String) = new SyncTable(databaseName, system, map, serverInfo.sendRef)
  def aclient(databaseName: String) = new AsyncTable(databaseName, system, serverInfo.sendRef)
}

class Server(serverConfig: Json) extends Actor {
  implicit val timeout = Timeout(5 seconds)

  def deletePath(f: File) {
    if (f.isDirectory()) {
      for (f1: File <- f.listFiles()) {
        deletePath(f1);
      }
    }
    f.delete();
  }

  val serverName = jgetString(serverConfig, "host") + ":" + jgetInt(serverConfig, "port")
  val path = jgetString(serverConfig, "path")
  val fname = path + "/" + "@server" +"/" + serverName
  val f = new File(fname)
  val exists = f.exists()
  val system = context.system
  val store = new Store(context, "@server", fname, ! exists)
  val storeTable = store.getTable(serverName)
  val listener = system.actorOf(Props[Listener])
  system.eventStream.subscribe(listener, classOf[DeadLetter])
  system.eventStream.subscribe(listener, classOf[RemoteLifeCycleEvent])

  var databases = TreeMap[String, DatabaseInfo]()
  val restPort = jgetInt(serverConfig, "rest")
  if (restPort != 0) {
    RestClient1.system = system
    // TODO should be better synchronized
    RestClient1.databases = databases
    Http.start(system, restPort)
  }

  if (exists) {
    var done = false
    var key = storeTable.first()
    do {
      key match {
        case Some(databaseName: String) => {
          val dbConf = storeTable.getMeta(databaseName) match {
            case Some(s: String) => s
            case None => ""
          }
          val dbConfig = Json(dbConf)
          val map = new NetworkMap(system, databaseName, dbConfig)
          val config = DatabaseConfig(databaseName, dbConfig)
          val info = new DatabaseInfo(system, serverName, databaseName, map, config, "stop")
          databases += (databaseName -> info)
          RestClient1.databases = databases // should be better synchronized
          key = storeTable.next(databaseName, false)
        }
        case None => done = true
      }
    } while (!done)
  }

  def receive = {
    case ("newDatabase", databaseName: String, dbConf: String) => {
      val dbConfig = Json(dbConf)
      val map = new NetworkMap(system, databaseName, dbConfig)
      var config = DatabaseConfig(databaseName, dbConfig)
      if (databases.contains(databaseName)) {
        sender ! "AlreadyPresent"
      } else {
        storeTable.putMeta(databaseName, dbConf)
        val database = system.actorOf(Props(new ServerDatabase(config, map, serverConfig, true)), name = databaseName)
        val info = new DatabaseInfo(system, serverName, databaseName, map, config, "starting")
        databases += (databaseName -> info)
        RestClient1.databases = databases // should be better synchronized
        val f = database ? ("start1")
        val x = Await.result(f,5 seconds)
        sender ! Codes.Ok
        println("Created Database " + databaseName)
      }
    }
    case ("stopDatabase1", databaseName: String) => {
      databases.get(databaseName) match {
        case Some(info) => {
          info.state = "stopping"
          val database = info.map.serverInfo(serverName).dbRef
          val f = database ? ("stop1")
          val v = Await.result(f, 5 seconds)
          sender ! Codes.Ok
        }
        case None => {
          sender ! "exist"
        }
      }
    }
    case ("stopDatabase2", databaseName: String) => {
      databases.get(databaseName) match {
        case Some(info) => {
          info.state = "stop"
          val database = info.map.serverInfo(serverName).dbRef
          val f = database ? ("stop2")
          val v = Await.result(f, 5 seconds)
          val stopped = gracefulStop(database, 5 seconds)(system)
          Await.result(stopped, 5 seconds)
          sender ! Codes.Ok
          println("Database stopped " + databaseName)
        }
        case None => {
          sender ! "exist"
        }
      }
    }
    case ("startDatabase1", databaseName: String) => {
      databases.get(databaseName) match {
        case Some(info) => {
          val database = system.actorOf(Props(new ServerDatabase(info.config,info.map, serverConfig, false)), name = databaseName)
          val f = database ? ("start1")
          Await.result(f, 5 seconds)
          info.state = "starting"
          sender ! Codes.Ok
          println("Database starting " + databaseName)
        }
        case None => {
          sender ! "exist"
        }
      }
    }
    case ("startDatabase2", databaseName: String) => {
      databases.get(databaseName) match {
        case Some(info) => {
          val database = info.map.serverInfo(serverName).dbRef
          val f = database ? ("start2")
          Await.result(f, 5 seconds)
          info.state = "active"
          sender ! Codes.Ok
          println("Database started " + databaseName)
        }
        case None => {
          sender ! "exist"
        }
      }
    }
    case ("deleteDatabase", databaseName: String) => {
      databases.get(databaseName) match {
        case Some(info) => {
          println("Deleting database " + databaseName)
          val path = jgetString(serverConfig, "path")
          val fname = path + "/" + databaseName
          val f = new File(fname)
          deletePath(f)
          databases -= databaseName
          RestClient1.databases = databases // should be better synchronized
          sender ! Codes.Ok
          storeTable.remove(databaseName)
          println("Database deleted " + databaseName)
        }
        case None => {
          sender ! "exist"
        }
      }
    }
    case ("databases") => {
      var result = JsonArray()
      for ((name, info) <- databases) {
        val o = JsonObject("name" -> name, "state" -> info.state)
        result = o +: result
      }
      sender ! (Codes.Ok, Compact(result.reverse))
    }
    case ("database", databaseName: String) => {
      databases.get(databaseName) match {
        case Some(info) => {
          val meta = storeTable.getMeta(databaseName) match {
            case Some(s: String) => s
            case None => ""
          }
          sender ! (Codes.Ok, meta)
        }
        case None => sender ! (Codes.NotPresent, "")
      }
    }
    case ("start") => {
      sender ! Codes.Ok
    }
    case ("stop") => {
      if (restPort != 0) Http.stop
      storeTable.close()
      sender ! Codes.Ok
    }
    case x: String => println("databaseFail:" + x)
  }
}

object Server {
  implicit val timeout = Timeout(200 seconds)
  private var server:ActorRef = null
  var system:ActorSystem = null
  
  def start(config: Json):ActorSystem = {
    // TODO get port from config
    system = ActorSystem("ostore", ConfigFactory.load.getConfig("server"))
    server = system.actorOf(Props(new Server(config)), name = "@svr")
    val f = server ? ("start")
    Await.result(f,200 seconds)
    system
  }
  
  def stop {
    // TODO make sure server is started and no active databases
    val f = server ? ("stop")
    Await.result(f, 5 seconds)
    val f1 = gracefulStop(server, 5 seconds)(system) // will stop all its children too!
    Await.result(f1,5 seconds)
  }
  
  def main(args: Array[String]) {
    val fname = if (args.size > 0) { args(0)} else { "config/server.json"}
    println("Server Starting")
    val config = Source.fromFile(fname).mkString
    start(Json(config))
  }
}

