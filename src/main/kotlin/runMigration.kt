package main

import com.github.javaparser.ast.expr.MethodCallExpr
import com.intellij.codeInspection.BaseJavaLocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorAction
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.*
import java.util.stream.Stream

/**
 * Created by aleksyuk on 4/28/16.
 */

class ShowClonesHandler : EditorAction(ShowClonesHandler.EditorHandler()) {

    private class EditorHandler : EditorActionHandler() {
        override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext) {
            val project = editor.project!! // TODO
            val files = project.getAllPsiJavaFiles().toList()
            Files.write(Paths.get("/tmp/duke.txt"), files.toString().toByteArray());
            val acceptedNames = listOf("get", "before", "after", "post", "put", "delete");
//            for (file in files) {
////                val visitor = CloneInspectionVisitor()
//                visitor.visitJavaFile(file)
//                val methods = visitor.methods
//                Files.write(Paths.get("/tmp/duke2.txt"), methods.toString().toByteArray(), StandardOpenOption.APPEND, StandardOpenOption.CREATE);
//            }
//            val project = editor.project!!
//            project.getCloneManager().filteredClonesApply { ClonesViewProvider.showClonesData(project, it) }
        }
    }
}

fun Project.getAllPsiJavaFiles() =
        PsiManager.getInstance(this).findDirectory(baseDir)!!.getPsiJavaFiles()

fun PsiDirectory.getPsiJavaFiles() =
        this.traverseWithDepth { it.subdirectories.asSequence()}
        .flatMap { it -> it.first.files.asSequence()}
        .filter { it -> it is PsiJavaFile }
        .filter { isWritable }
        .map { it -> it as PsiJavaFile }

//fun <T> T.depthFirstTraverse(children: (T) -> Sequence<T>): Sequence<T> =
//        Stream.of(this).concat( children(this).flatMap { it.depthFirstTraverse(children) } )
//
//fun <T> Stream<out T>.concat(stream: Stream<out T>) = Stream.concat(this, stream)
//


fun <T> T.traverse(children: (T)->Sequence<T>): Sequence<T> =
        sequenceOf(this) + children(this).flatMap { it.traverse(children) }

fun <T> T.traverseWithDepth(children: (T)->Sequence<T>): Sequence<Pair<T, Int>> =
(this to 0).traverse {
    val (value, depth) = it
    children(value).map { it to depth + 1 }
}

class CloneInspectionVisitor(val holder: ProblemsHolder) : JavaElementVisitor() {
    override fun visitMethod(method: PsiMethod) {
        methods.add(method)
    }

    companion object {
        val methods: MutableList<PsiMethod> = ArrayList();
    }
}

class InspectionProvider : BaseJavaLocalInspectionTool() {

    override fun getGroupDisplayName() = "Analyze"

    override fun getShortName() = "CloneDetection"

    override fun getDisplayName() = "Clone detection"

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
            CloneInspectionVisitor(holder)
}