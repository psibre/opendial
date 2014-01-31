// =================================================================                                                                   
// Copyright (C) 2011-2015 Pierre Lison (plison@ifi.uio.no)                                                                            
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

package opendial.modules;


import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

import opendial.DialogueSystem;
import opendial.arch.DialException;
import opendial.arch.Logger;
import opendial.arch.Settings;
import opendial.bn.distribs.other.EmpiricalDistribution;
import opendial.bn.distribs.other.MarginalEmpiricalDistribution;
import opendial.bn.nodes.ChanceNode;
import opendial.bn.values.DoubleVal;
import opendial.datastructs.Assignment;
import opendial.inference.approximate.LikelihoodWeighting;
import opendial.inference.approximate.SamplingProcess;
import opendial.inference.approximate.WeightedSample;
import opendial.inference.queries.UtilQuery;
import opendial.state.DialogueState;


/**
 * Module employed during simulated dialogues to automatically estimate a utility model
 * from rewards produced by the simulator.
 * 
 * @author  Pierre Lison (plison@ifi.uio.no)
 * @version $Date::                      $
 */
public class RewardLearner implements Module {

	// logger
	public static Logger log = new Logger("RewardLearner", Logger.Level.DEBUG);

	// the dialogue system
	DialogueSystem system;

	// previous dialogue states with a decision network.  The states are indexed
	// by the label of their action variables.
	Map<Set<String>, DialogueState> previousStates;


	/**
	 * Creates the reward learner for the dialogue system.
	 */
	public RewardLearner(DialogueSystem system) {
		this.system = system;
		previousStates = new HashMap<Set<String>, DialogueState>();
	}

	
	/**
	 * Does nothing.
	 */
	@Override
	public void start() {	}

	/**
	 * Does nothing.
	 */
	@Override
	public void pause(boolean shouldBePaused) {	}

	/**
	 * Returns true.
	 */
	@Override
	public boolean isRunning() {  return true;	}

	
	/**
	 * Triggers the reward learner.  The module is only triggered whenever a variable
	 * of the form R(assignment of action values) is included in the dialogue state by
	 * the simulator. In such case, the module checks whether a past dialogue state 
	 * contains a decision for these action variables, and if yes, update their parameters 
	 * to reflect the actual received reward.
	 * 
	 * @param state the dialogue state
	 * @param updatedVars the list of recently updated variables.
	 * 
	 */
	@Override
	public void trigger(DialogueState state, Collection<String> updatedVars) {

		for (String evidenceVar : state.getEvidence().getVariables()) {
			if (evidenceVar.startsWith("R(") && evidenceVar.endsWith(")")) {
				Assignment actualAction = Assignment.createFromString
						(evidenceVar.substring(2, evidenceVar.length()-1));
				double actualUtility = ((DoubleVal)state.getEvidence().getValue(evidenceVar)).getDouble();

				if (previousStates.containsKey(actualAction.getVariables())) {
					DialogueState previousState = previousStates.get(actualAction.getVariables());
					learnFromFeedback(previousState, actualAction, actualUtility);
				}
				state.clearEvidence(Arrays.asList(evidenceVar));
			}
		}
		
		if (!state.getActionNodeIds().isEmpty()) {
			try {
			previousStates.put(new HashSet<String>(state.getActionNodeIds()), state.copy());
			}
			catch (DialException e) {
				log.warning("cannot copy state: " + e);
			}
		}
	}

	
	/**
	 * Re-estimate the posterior distribution for the domain parameters in the dialogue
	 * state given the actual system decision and its resulting utility (provided by the
	 * simulator).
	 * 
	 * @param state the dialogue state
	 * @param actualAction the action that was selected
	 * @param actualUtility the resulting utility for the action.
	 */
	private void learnFromFeedback(DialogueState state, Assignment actualAction, 
			double actualUtility) {

		try {
			
			// determine the relevant parameters (discard the isolated ones)
			Set<String> relevantParams = new HashSet<String>();
			for (String param : state.getParameterIds()) {
				if (!state.getChanceNode(param).getOutputNodesIds().isEmpty()) {
					relevantParams.add(param);
				}
			}
			if (!relevantParams.isEmpty()) {

				// creates a new query thread
				SamplingProcess isquery = new SamplingProcess
						(new UtilQuery(state, relevantParams, actualAction), 
								Settings.nbSamples, Settings.maxSamplingTime);

				// extract and redraw the samples according to their weight.
				Stack<WeightedSample> samples = isquery.getSamples();

				for (WeightedSample sample : samples) {
					double weight = (1.0 / (Math.abs(sample.getUtility() - actualUtility) + 1));
					sample.addLogWeight(Math.log(weight));
				}
				samples = LikelihoodWeighting.redrawSamples(samples);

				// creates an empirical distribution from the samples
				EmpiricalDistribution empiricalDistrib = new EmpiricalDistribution();
				for (WeightedSample sample : samples) {
					sample.trim(relevantParams);
					empiricalDistrib.addSample(sample);
				}

				for (String param : relevantParams) {
					ChanceNode paramNode = system.getState().getChanceNode(param);
					MarginalEmpiricalDistribution newDistrib = new MarginalEmpiricalDistribution
							(Arrays.asList(param), paramNode.getInputNodeIds(), empiricalDistrib);
					paramNode.setDistrib(newDistrib);
				}
			}
		}
		catch (DialException e) {
			log.warning("could not learn from action feedback: " + e);
		}

	}




}
