package mill.eval

import mill.api.Val
import mill.api.Result
import mill.constants.OutFiles
import mill.define.{InputImpl, NamedTask, SelectMode, Task, TaskResult}
import mill.exec.{CodeSigUtils, Execution, Plan}
import mill.internal.SpanningForest
import mill.internal.SpanningForest.breadthFirst

private[mill] object SelectiveExecution {
  case class Metadata(inputHashes: Map[String, Int], methodCodeHashSignatures: Map[String, Int])

  implicit val rw: upickle.default.ReadWriter[Metadata] = upickle.default.macroRW

  object Metadata {
    def compute(
        evaluator: Evaluator,
        tasks: Seq[NamedTask[?]]
    ): (Metadata, Map[Task[?], TaskResult[Val]]) = {
      compute0(evaluator, Plan.transitiveNamed(tasks))
    }

    def compute0(
        evaluator: Evaluator,
        transitiveNamed: Seq[NamedTask[?]]
    ): (Metadata, Map[Task[?], TaskResult[Val]]) = {
      val inputTasksToLabels: Map[Task[?], String] = transitiveNamed
        .collect { case task: InputImpl[_] =>
          task -> task.ctx.segments.render
        }
        .toMap

      val results = evaluator.execute(Seq.from(inputTasksToLabels.keys))

      val inputHashes = results
        .executionResults
        .results
        .flatMap { case (task, taskResult) =>
          inputTasksToLabels.get(task).map { l =>
            l -> taskResult.result.get.value.hashCode
          }
        }

      new Metadata(
        inputHashes,
        evaluator.methodCodeHashSignatures
      ) -> results.executionResults.results
    }
  }

  def computeHashCodeSignatures(
      transitiveNamed: Seq[NamedTask[?]],
      methodCodeHashSignatures: Map[String, Int]
  ): Map[String, Int] = {

    val (classToTransitiveClasses, allTransitiveClassMethods) =
      CodeSigUtils.precomputeMethodNamesPerClass(transitiveNamed)

    lazy val constructorHashSignatures = CodeSigUtils
      .constructorHashSignatures(methodCodeHashSignatures)

    transitiveNamed
      .map { namedTask =>
        namedTask.ctx.segments.render -> CodeSigUtils
          .codeSigForTask(
            namedTask,
            classToTransitiveClasses,
            allTransitiveClassMethods,
            methodCodeHashSignatures,
            constructorHashSignatures
          )
          .sum
      }
      .toMap
  }

  def computeDownstream(
      transitiveNamed: Seq[NamedTask[?]],
      oldHashes: Metadata,
      newHashes: Metadata
  ): (Set[Task[?]], Seq[Task[Any]]) = {
    val namesToTasks = transitiveNamed.map(t => (t.ctx.segments.render -> t)).toMap

    def diffMap[K, V](lhs: Map[K, V], rhs: Map[K, V]) = {
      (lhs.keys ++ rhs.keys)
        .iterator
        .distinct
        .filter { k => lhs.get(k) != rhs.get(k) }
        .toSet
    }

    val changedInputNames = diffMap(oldHashes.inputHashes, newHashes.inputHashes)
    val changedCodeNames = diffMap(
      computeHashCodeSignatures(transitiveNamed, oldHashes.methodCodeHashSignatures),
      computeHashCodeSignatures(transitiveNamed, newHashes.methodCodeHashSignatures)
    )

    val changedRootTasks = (changedInputNames ++ changedCodeNames)
      .flatMap(namesToTasks.get(_): Option[Task[?]])

    val allNodes = breadthFirst(transitiveNamed.map(t => t: Task[?]))(_.inputs)
    val downstreamEdgeMap = SpanningForest.reverseEdges(allNodes.map(t => (t, t.inputs)))

    (
      changedRootTasks,
      breadthFirst(changedRootTasks) { t =>
        downstreamEdgeMap.getOrElse(t.asInstanceOf[Task[Nothing]], Nil)
      }
    )
  }

  def saveMetadata(evaluator: Evaluator, metadata: SelectiveExecution.Metadata): Unit = {
    os.write.over(
      evaluator.outPath / OutFiles.millSelectiveExecution,
      upickle.default.write(metadata, indent = 2)
    )
  }

  case class ChangedTasks(
      resolved: Seq[NamedTask[?]],
      changedRootTasks: Set[NamedTask[?]],
      downstreamTasks: Seq[NamedTask[?]],
      results: Map[Task[?], TaskResult[Val]]
  )

  def computeChangedTasks(
      evaluator: Evaluator,
      tasks: Seq[String]
  ): Result[ChangedTasks] = {
    evaluator.resolveTasks(
      tasks,
      SelectMode.Separated,
      evaluator.allowPositionalCommandArgs
    ).map(computeChangedTasks0(evaluator, _))
  }

  def computeChangedTasks0(evaluator: Evaluator, tasks: Seq[NamedTask[?]]): ChangedTasks = {
    val oldMetadataTxt = os.read(evaluator.outPath / OutFiles.millSelectiveExecution)

    if (oldMetadataTxt == "") ChangedTasks(tasks, tasks.toSet, tasks, Map.empty)
    else {
      val transitiveNamed = Plan.transitiveNamed(tasks)
      val oldMetadata = upickle.default.read[SelectiveExecution.Metadata](oldMetadataTxt)
      val (newMetadata, results) = SelectiveExecution.Metadata.compute0(evaluator, transitiveNamed)
      val (changedRootTasks, downstreamTasks) =
        SelectiveExecution.computeDownstream(transitiveNamed, oldMetadata, newMetadata)

      ChangedTasks(
        tasks,
        changedRootTasks.collect { case n: NamedTask[_] => n },
        downstreamTasks.collect { case n: NamedTask[_] => n },
        results
      )
    }
  }

  def resolve0(evaluator: Evaluator, tasks: Seq[String]): Result[Array[String]] = {
    for {
      resolved <- evaluator.resolveTasks(tasks, SelectMode.Separated)
      changedTasks <- SelectiveExecution.computeChangedTasks(evaluator, tasks)
    } yield {
      val resolvedSet = resolved.map(_.ctx.segments.render).toSet
      val downstreamSet = changedTasks.downstreamTasks.map(_.ctx.segments.render).toSet
      resolvedSet.intersect(downstreamSet).toArray.sorted
    }
  }

  def resolveChanged(evaluator: Evaluator, tasks: Seq[String]): Result[Seq[String]] = {
    for (changedTasks <- SelectiveExecution.computeChangedTasks(evaluator, tasks)) yield {
      changedTasks.changedRootTasks.map(_.ctx.segments.render).toSeq.sorted
    }
  }

  def resolveTree(evaluator: Evaluator, tasks: Seq[String]): Result[ujson.Value] = {
    for (changedTasks <- SelectiveExecution.computeChangedTasks(evaluator, tasks)) yield {
      val taskSet = changedTasks.downstreamTasks.toSet[Task[?]]
      val plan = Plan.plan(Seq.from(changedTasks.downstreamTasks))
      val indexToTerminal = plan.sortedGroups.keys().toArray.filter(t => taskSet.contains(t))

      val interGroupDeps = Execution.findInterGroupDeps(plan.sortedGroups)

      val reverseInterGroupDeps = SpanningForest.reverseEdges(interGroupDeps)

      val (vertexToIndex, edgeIndices) =
        SpanningForest.graphMapToIndices(indexToTerminal, reverseInterGroupDeps)

      val json = SpanningForest.writeJson(
        indexEdges = edgeIndices,
        interestingIndices = indexToTerminal.indices.toSet,
        render = indexToTerminal(_).toString
      )

      // Simplify the tree structure to only show the direct paths to the tasks
      // resolved directly, removing the other branches, since those tasks are
      // the ones that the user probably cares about
      val resolvedTaskLabels = changedTasks.resolved.map(_.ctx.segments.render).toSet
      def simplifyJson(j: ujson.Obj): Option[ujson.Obj] = {
        val map = j.value.flatMap {
          case (k, v: ujson.Obj) =>
            simplifyJson(v)
              .map((k, _))
              .orElse(Option.when(resolvedTaskLabels.contains(k)) { k -> v })
          case _ => ???
        }
        Option.when(map.nonEmpty)(ujson.Obj.from(map))
      }

      simplifyJson(json).getOrElse(ujson.Obj())
    }
  }
}
