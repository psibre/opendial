// =================================================================                                                                   
// Copyright (C) 2011-2013 Pierre Lison (plison@ifi.uio.no)                                                                            
//                                                                                                                                     
// This library is free software; you can redistribute it and/or                                                                       
// modify it under the terms of the GNU Lesser General Public License                                                                  
// as published by the Free Software Foundation; either version 2.1 of                                                                 
// the License, or (at your option) any later version.                                                                                 
//                                                                                                                                     
// This library is distributed in the hope that it will be useful, but                                                                 
// WITHOUT ANY WARRANTY; without even the implied warranty of                                                                          
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU                                                                    
// Lesser General Public License for more details.                                                                                     
//                                                                                                                                     
// You should have received a copy of the GNU Lesser General Public                                                                    
// License along with this program; if not, write to the Free Software                                                                 
// Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA                                                                           
// 02111-1307, USA.                                                                                                                    
// =================================================================                                                                   

package opendial.simulation;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import opendial.arch.DialException;
import opendial.arch.Logger;
import opendial.arch.Settings;
import opendial.arch.StateListener;
import opendial.arch.Logger.Level;
import opendial.bn.Assignment;
import opendial.bn.distribs.ProbDistribution;
import opendial.bn.distribs.discrete.DiscreteProbDistribution;
import opendial.bn.distribs.discrete.SimpleTable;
import opendial.bn.distribs.empirical.DepEmpiricalDistribution;
import opendial.bn.distribs.utility.UtilityTable;
import opendial.bn.nodes.BNode;
import opendial.bn.nodes.ChanceNode;
import opendial.bn.values.Value;
import opendial.bn.values.ValueFactory;
import opendial.domains.Domain;
import opendial.domains.Model;
import opendial.domains.rules.DecisionRule;
import opendial.gui.GUIFrame;
import opendial.inference.InferenceAlgorithm;
import opendial.inference.queries.ProbQuery;
import opendial.inference.queries.UtilQuery;
import opendial.state.DialogueState;
import opendial.utils.CombinatoricsUtils;

public class UserSimulator extends Thread {

	// logger
	public static Logger log = new Logger("Simulator", Logger.Level.DEBUG);

	DialogueState systemState;

	Model<DecisionRule> rewardModel;

	DialogueState realState;
	
	boolean paused = false;

	long systemActionStamp = 0;
	
	int nbTurns = 0;
	
	double accReturn = 0;
	
	public UserSimulator(DialogueState systemState, Domain domain) throws DialException {
		this.systemState = systemState;
		this.realState = domain.getInitialState().copy();
		
		realState.setName("simulator");
		for (Model<?> model : domain.getModels()) {
			if (!(model.getModelType().equals(DecisionRule.class))) {
				realState.attachModule(model);
			}
			else {
				rewardModel = (Model<DecisionRule>)model;
			}
		}
	}
	
	public DialogueState getRealState() {
		return realState;
	}

	public void startSimulator() {

		realState.startState();

		this.start();
	}

	
	
	@Override
	public void run() {
		while (true) {
			try {
			while (!systemState.isStable() || paused) {
				Thread.sleep(100);
			}
			performTurn();
			}
			catch (Exception e) {
				log.warning("simulator error: " + e);
			}
		}
	}
	
	public void pause(boolean shouldBePaused) {
		paused = shouldBePaused;
	}

	public void performTurn() {

		try {
			log.debug("--------");
			
		/**	if (systemState.getNetwork().hasChanceNode("i_u")) {
				log.debug("system-estimated intention: " + systemState.getContent("i_u", true));
			}
			if (systemState.getNetwork().hasChanceNode("a_u")) {
				log.debug("system-estimated a_u: " + systemState.getContent("a_u", true));
			} */
			
			if (systemState.getNetwork().hasChanceNode("i_u")) {
				log.debug("i_u: " + systemState.getContent("i_u", true).toString().replace("\n", ", "));
			}
			if (systemState.getNetwork().hasChanceNode("a_u")) {
				log.debug("a_u: " + systemState.getContent("a_u", true).toString().replace("\n", ", "));
			}
			if (systemState.getNetwork().hasChanceNode("carried")) {
				log.debug("carried: " + systemState.getContent("carried", true).toString().replace("\n", ", "));
			}
			if (systemState.getNetwork().hasChanceNode("perceived")) {
				log.debug("perceived: " + systemState.getContent("perceived", true).toString().replace("\n", ", "));
			}
			
			Assignment action = getSystemAction();
			log.debug("system action: " + action);
			double returnValue = getReturn(action);
			
			log.debug("reward value: " + returnValue);
			accReturn += returnValue;				
			
			Assignment sampled = addSystemAction(action);
			log.debug("sampled elements after action: " + sampled);
			
			if (realState.getNetwork().hasChanceNode("floor")) {
				double prob = realState.getContent("floor", true).toDiscrete().getProb(
						new Assignment(), new Assignment("floor", "start"));
				if (prob > 0.98) {
					log.debug("accumulated return: " + accReturn);
					accReturn = 0;
				}
			}
			
			DiscreteProbDistribution obs = getNextObservation(sampled);
			Assignment evidence = new Assignment();
			evidence.addPair("i_u", sampled.getValue("i_u"));
			evidence.addPair("perceived", sampled.getValue("perceived"));
			evidence.addPair("carried", sampled.getValue("carried"));
			realState.addContent(evidence, "evidence");

			log.debug("adding observation: " + obs.toString().replace("\n", ", "));
			systemState.addContent(realState.getContent("a_uother", true), "sim1");

			systemState.addContent(obs, "sim2");

			nbTurns++;
			
			if (nbTurns == 10) {
				log.debug("===> estimate for theta_1: " + systemState.getContent("theta_1", true));
				log.debug("===> estimate for theta_2: " + systemState.getContent("theta_2", true));
				log.debug("===> estimate for theta_3: " + systemState.getContent("theta_3", true));
				log.debug("===> estimate for theta_4: " + systemState.getContent("theta_4", true));
				log.debug("===> estimate for theta_5: " + systemState.getContent("theta_5", true));
				log.debug("===> estimate for theta_6: " + systemState.getContent("theta_6", true));
				log.debug("===> estimate for theta_7: " + systemState.getContent("theta_7", true));
				log.debug("===> estimate for theta_8: " + systemState.getContent("theta_8", true));
				log.debug("===> estimate for theta_9: " + systemState.getContent("theta_9", true));
				log.debug("===> estimate for theta_10: " + systemState.getContent("theta_10", true));
				log.debug("===> estimate for theta_11: " + systemState.getContent("theta_11", true));
				log.debug("===> estimate for theta_12: " + systemState.getContent("theta_12", true));
				nbTurns = 0;
			}
			
		}
		catch (Exception e) {
			log.warning("could not update simulator: " + e.toString());
			e.printStackTrace();
		}
	}


	private Assignment getSystemAction() throws DialException {
		if (systemState.getUpdateStamp("a_m") - systemActionStamp > 0) {
			systemActionStamp = System.currentTimeMillis();
			SimpleTable actionDistrib = systemState.getContent("a_m", true).
					toDiscrete().getProbTable(new Assignment());
			if (actionDistrib.getRows().size() ==1) {
				return actionDistrib.getRows().iterator().next();
			}
		}
		return new Assignment("a_m", ValueFactory.none());
	}


	
	private Assignment addSystemAction(Assignment action) throws DialException {

		realState.addContent(action, "systemAction");
		
		Assignment sampled = new Assignment();
		List<BNode> sequence = realState.getNetwork().getSortedNodes();
		Collections.reverse(sequence);
		for (BNode n : sequence) {
			if (n instanceof ChanceNode && !n.getId().equals("a_u^p")) {
				Value val = ((ChanceNode)n).sample(sampled);
				sampled.addPair(n.getId(), val);
			}	
		}
		sampled.addPair("i_u", "Move(Forward)");
		sampled.addPair("a_u0", "Move(Forward)");
		return sampled;
	}
	
	
	private DiscreteProbDistribution getNextObservation(Assignment sampled) throws DialException {
		
		try {
	//		log.debug("sampled: " + sampled);
			InferenceAlgorithm algo = Settings.getInstance().inferenceAlgorithm.newInstance();
			ProbQuery query = new ProbQuery(realState.getNetwork(), Arrays.asList("perceived", "carried", "a_u^p"), sampled);
			ProbDistribution distrib = algo.queryProb(query);
			distrib.modifyVarId("a_u^p", "a_u");
	//		log.debug("resulting distrib: " + distrib);
			
			return distrib.toDiscrete();
		}
		catch (Exception e) {
			throw new DialException("cannot extract next observation: " + e);
		}
	}
	

	
	private double getReturn(Assignment action) throws DialException {
		DialogueState tempState = realState.copy();
		tempState.removeAllModules();
		tempState.attachModule(rewardModel);
		
		try {
			tempState.addContent(action, "systemAction");
			InferenceAlgorithm algo = Settings.getInstance().inferenceAlgorithm.newInstance();
			UtilityTable utilDistrib = algo.queryUtil(new UtilQuery(tempState, new LinkedList<String>()));
			return utilDistrib.getUtil(new Assignment());
		}
		catch (Exception e) {
			log.warning("could not extract return: " + e);
		}
		return 0.0;
	}



	final class StateUpdater extends Thread {
		
		DialogueState state;
		SimpleTable table;
		
		public StateUpdater(DialogueState state, SimpleTable table) {
			this.state = state;
			this.table = table;
		}
		
		public void run() {
			try {
				Thread.sleep(100);
			state.addContent(table, "GUI");
			}
			catch (Exception e) {
				log.warning("cannot update state with user utterance");
			}
		}
	}
	
}
