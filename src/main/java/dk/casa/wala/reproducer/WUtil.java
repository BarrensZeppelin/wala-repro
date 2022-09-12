package dk.casa.wala.reproducer;

import com.ibm.wala.classLoader.Language;
import com.ibm.wala.core.util.config.AnalysisScopeReader;
import com.ibm.wala.demandpa.alg.DemandRefinementPointsTo;
import com.ibm.wala.demandpa.alg.refinepolicy.NeverRefineCGPolicy;
import com.ibm.wala.demandpa.alg.refinepolicy.NeverRefineFieldsPolicy;
import com.ibm.wala.demandpa.alg.refinepolicy.RefinementPolicyFactory;
import com.ibm.wala.demandpa.alg.refinepolicy.SinglePassRefinementPolicy;
import com.ibm.wala.demandpa.alg.statemachine.DummyStateMachine;
import com.ibm.wala.demandpa.alg.statemachine.StateMachineFactory;
import com.ibm.wala.demandpa.flowgraph.IFlowLabel;
import com.ibm.wala.demandpa.util.MemoryAccessMap;
import com.ibm.wala.demandpa.util.SimpleMemoryAccessMap;
import com.ibm.wala.ipa.callgraph.AnalysisCacheImpl;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.IAnalysisCacheView;
import com.ibm.wala.ipa.callgraph.cha.CHACallGraph;
import com.ibm.wala.ipa.callgraph.impl.Util;
import com.ibm.wala.ipa.callgraph.propagation.HeapModel;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.perf.Stopwatch;

import java.io.*;
import java.util.Collection;
import java.util.function.Supplier;

public class WUtil {
	public static AnalysisScope getWALAScope(Collection<File> jarFiles, Collection<String> directories) throws IOException {
		File scopeFile = File.createTempFile("scope", ".txt");
		scopeFile.deleteOnExit();

		try(PrintWriter writer = new PrintWriter(new BufferedWriter(new FileWriter(scopeFile)))) {
			// Java standard library
			writer.println("Primordial,Java,stdlib,none");
			writer.println("Primordial,Java,jarFile,primordial.jar.model");

			// Dependencies
			for(File jarFile : jarFiles)
				writer.format("Application,Java,jarFile,%s\n", jarFile.getPath());

			// Class file directories
			for(String classDirectory : directories)
				writer.format("Application,Java,binaryDir,%s\n", classDirectory);
		}

		return AnalysisScopeReader.instance.readJavaScope(scopeFile.getPath(), null, WUtil.class.getClassLoader());
	}

	public static DemandRefinementPointsTo initWala(CHACallGraph chaCG) throws CancelException {
		IClassHierarchy cha = chaCG.getClassHierarchy();
		AnalysisOptions options = new AnalysisOptions();
		IAnalysisCacheView cache = new AnalysisCacheImpl();
		// We also need a heap model to create InstanceKeys for allocation sites, etc.
		// Here we use a 0-1 CFA builder, which will give a heap abstraction similar to
		// context-insensitive Andersen's analysis
		HeapModel heapModel = Util.makeZeroOneCFABuilder(Language.JAVA, options, cache, cha);
		// The MemoryAccessMap helps the demand analysis find matching field reads and writes
		MemoryAccessMap mam = new SimpleMemoryAccessMap(chaCG, heapModel, false);
		// The StateMachineFactory helps in tracking additional states like calling contexts.
		// For context-insensitive analysis we use a DummyStateMachine.Factory
		StateMachineFactory<IFlowLabel> stateMachineFactory = new DummyStateMachine.Factory<>();
		DemandRefinementPointsTo drpt = DemandRefinementPointsTo.makeWithDefaultFlowGraph(
				chaCG, heapModel, mam, cha, options, stateMachineFactory);
		// The RefinementPolicyFactory determines how the analysis refines match edges (see PLDI'06
		// paper).  Here we use a policy that does not perform refinement and just uses a fixed budget
		// for a single pass
		RefinementPolicyFactory refinementPolicyFactory =
				new SinglePassRefinementPolicy.Factory(
						new NeverRefineFieldsPolicy(), new NeverRefineCGPolicy(), 1000);
		drpt.setRefinementPolicyFactory(refinementPolicyFactory);
		return drpt;
	}

	public static void time(String name, Runnable f) {
		Stopwatch watch = new Stopwatch();
		watch.start();
		f.run();
		watch.stop();
		System.out.format("%s took %.2fs\n", name, (float)watch.getElapsedMillis()/1000.f);
	}

	public static <T> T time(String name, Supplier<T> f) {
		Stopwatch watch = new Stopwatch();
		watch.start();
		T r = f.get();
		watch.stop();
		System.out.format("%s took %.2fs\n", name, (float)watch.getElapsedMillis()/1000.f);
		return r;
	}
}
