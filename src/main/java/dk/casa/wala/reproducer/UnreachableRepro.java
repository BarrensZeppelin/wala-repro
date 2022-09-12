package dk.casa.wala.reproducer;

import com.ibm.wala.classLoader.Language;
import com.ibm.wala.ipa.callgraph.*;
import com.ibm.wala.ipa.callgraph.cha.CHACallGraph;
import com.ibm.wala.ipa.callgraph.impl.Util;
import com.ibm.wala.ipa.callgraph.propagation.SSAPropagationCallGraphBuilder;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.util.CancelException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class UnreachableRepro {
	public static void main(String[] args) throws ClassHierarchyException, CancelException, IOException {
		//AnalysisScope scope = AnalysisScope.createJavaAnalysisScope();
		AnalysisScope scope = WUtil.getWALAScope(Collections.emptyList(), List.of("target/classes"));
		ClassHierarchy cha = ClassHierarchyFactory.make(scope);
		CHACallGraph chaCG = new CHACallGraph(cha);

		List<Entrypoint> entrypoints = new ArrayList<>();
		Util.makeMainEntrypoints(cha, "Ldk/casa/wala/reproducer/UnreachableStreamExample").forEach(entrypoints::add);

		WUtil.time("CHA CG Init", () -> {
			try {
				chaCG.init(entrypoints);
			} catch (CancelException e) {
				throw new RuntimeException(e);
			}
		});

		IAnalysisCacheView cache = new AnalysisCacheImpl();
		AnalysisOptions options = new AnalysisOptions();
		options.setEntrypoints(entrypoints);

		SSAPropagationCallGraphBuilder builder = Util.makeZeroCFABuilder(Language.JAVA, options, cache, cha);
		CallGraph cg = WUtil.time("01 CFA CG Init", () -> {
			try {
				return builder.makeCallGraph(options);
			} catch (CancelException e) {
				throw new RuntimeException(e);
			}
		});

		//DemandRefinementPointsTo drpt = WUtil.initWala(chaCG);

		System.out.println("Callgraph initialised");

		cg.stream().filter(node -> node.getMethod().getDeclaringClass().getName().toString().endsWith("UnreachableStreamExample"))
				.forEach(System.out::println);

		/*
		IClass cls = lookupClass(context.getOwner());
		IMethod method = cls.getMethod(new Selector(Atom.findOrCreateUnicodeAtom(mn.name), Descriptor.findOrCreateUTF8(mn.desc)));

		CGNode node = chaCG.getNode(method, EVERYWHERE);
		if(node == null) { // ???
			queryStats.add("CGNodeNull");
			System.err.println("CGNodeNull?");
			return Optional.empty();
		}

		// This piece of code finds calls in the method that match the given name and descriptor
		IR ir = node.getIR();
		List<SSAAbstractInvokeInstruction> matchingCalls = new ArrayList<>();
		ir.iterateCallSites().forEachRemaining(csr -> {
			SSAAbstractInvokeInstruction invoke = ir.getCalls(csr)[0];
			MethodReference target = invoke.getDeclaredTarget();
			if(target.getName().toString().equals(minsn.name) && target.getDescriptor().toUnicodeString().equals(minsn.desc))
				matchingCalls.add(invoke);
		});
		 */
	}
}
