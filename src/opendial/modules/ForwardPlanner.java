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


import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import opendial.DialogueSystem;
import opendial.arch.AnytimeProcess;
import opendial.arch.DialException;
import opendial.arch.Logger;
import opendial.arch.Settings;
import opendial.bn.distribs.discrete.CategoricalTable;
import opendial.bn.distribs.utility.UtilityTable;
import opendial.datastructs.Assignment;
import opendial.domains.Model;
import opendial.state.DialogueState;


/**
 * Online forward planner for OpenDial. The planner constructs a lookahead tree (with a 
 * depth corresponding to the planning horizon) that explores possible actions and their
 * expected consequences on the future dialogue state. The final utility values for each
 * action is then estimated, and the action with highest utility is selected.
 * 
 * <p>The planner is an anytime process.  It can be interrupted at any time and yield a 
 * result. The quality of the utility estimates is of course improving over time.
 * 
 * <p>The planning algorithm is described in pages 121-123 of Pierre Lison's PhD thesis 
 * [http://folk.uio.no/plison/pdfs/thesis/thesis-plison2013.pdf]
 * 
 * @author  Pierre Lison (plison@ifi.uio.no)
 * @version $Date::                      $
 */
public class ForwardPlanner implements Module {

	// logger
	public static Logger log = new Logger("ForwardPlanner", Logger.Level.DEBUG);


	/** Maximum number of actions to consider at each planning step */
	public static int NB_BEST_ACTIONS = 100;
	
	/** Maximum number of alternative observations to consider at each planning step */
	public static int NB_BEST_OBSERVATIONS = 3;
	
	/** Minimum probability for the generated observations */
	public static double MIN_OBSERVATION_PROB = 0.1;
	
	DialogueSystem system;

	boolean paused = false;

	
	/**
	 * Constructs a forward planner for the dialogue system.
	 * @param system
	 */
	public ForwardPlanner(DialogueSystem system) {
		this.system = system;
	}

	/**
	 * Pauses the forward planner
	 */
	@Override
	public void pause(boolean shouldBePaused) {	
		paused = shouldBePaused;
	}

	/**
	 * Does nothing.
	 */
	@Override
	public void start()  {	}


	/**
	 * Returns true if the planner is not paused.
	 */
	@Override
	public boolean isRunning() {
		return !paused;
	}

	/**
	 * Triggers the planning process.
	 */
	@Override
	public void trigger(DialogueState state, Collection<String> updatedVars) {
		if (!paused && !state.getActionNodeIds().isEmpty()) {
			try {
				PlannerProcess process = new PlannerProcess(state);
				process.start();
				process.join();
			} 
			catch (InterruptedException e) { e.printStackTrace(); }
		}
	} 


	/**
	 * Planner process, which can be terminated before the end of the horizon
	 * @author  Pierre Lison (plison@ifi.uio.no)
	 * @version $Date::                      $
	 */
	public class PlannerProcess extends AnytimeProcess {

		DialogueState initState;

		/**
		 * Creates the planning process.  Timeout is set to twice the maximum sampling time.
		 */
		public PlannerProcess(DialogueState initState) {
			super(Settings.maxSamplingTime * 2);
			this.initState = initState;
		}

		boolean isTerminated = false;

		/**
		 * Runs the planner until the horizon has been reached, or the planner has run out
		 * of time.  Adds the best action to the dialogue state.
		 */
		@Override
		public void run() {
			try {
				UtilityTable evalActions =getQValues(initState, system.getSettings().horizon);
				//		ForwardPlanner.log.debug("Q-values: " + evalActions);
				Assignment bestAction =  evalActions.getBest().getKey(); 

				if (evalActions.getUtil(bestAction) < 0.001) {
					bestAction = Assignment.createDefault(bestAction.getVariables());
				}
				initState.addToState(new CategoricalTable(bestAction.removePrimes()));
				isTerminated = true;
			}
			catch (Exception e) {
				log.warning("could not perform planning, aborting action selection: " + e);
				e.printStackTrace();
			}
		}



		/**
		 * Returns the Q-values for the dialogue state, assuming a particular horizon.
		 * 
		 * @param state the dialogue state
		 * @param horizon the planning horizon
		 * @return the estimated utility table for the Q-values
		 * @throws DialException
		 */
		private UtilityTable getQValues (DialogueState state, int horizon) throws DialException {

			Set<String> actionNodes = state.getActionNodeIds();
			if (actionNodes.isEmpty()) {
				return new UtilityTable();
			}
			UtilityTable rewards = state.queryUtil(actionNodes);

			if (horizon ==1) {
				return rewards;
			}

			UtilityTable qValues = new UtilityTable();

			for (Assignment action : rewards.getRows()) {
				double reward = rewards.getUtil(action);
				qValues.setUtil(action, reward);

				if (horizon > 1 && !isTerminated && !paused && hasTransition(action)) {

					DialogueState copy = state.copy();
					addContent(copy, new CategoricalTable(action.removePrimes()));

					if (!action.isDefault()) {
						double expected = system.getSettings().discountFactor * getExpectedValue(copy, horizon - 1);
						qValues.setUtil(action, qValues.getUtil(action) + expected);
					}	
				}
			}
			return qValues;
		}


		/**
		 * Adds a particular content to the dialogue state
		 * @param state the dialogue state
		 * @param newContent the content to add
		 * @throws DialException if the update operation could not be performed
		 */
		private void addContent(DialogueState state, CategoricalTable newContent) 
				throws DialException {
			state.addToState(newContent);
			
			while (!state.getNewVariables().isEmpty()) {
				Set<String> toProcess = state.getNewVariables();
				state.reduce();	
				for (Model model : system.getDomain().getModels()) {
					model.trigger(state, toProcess);
				}
			}
		}


		/**
		 * Returns true if the dialogue domain specifies a transition model for 
		 * the particular action assignment.
		 * 
		 * @param action the assignment of action values
		 * @return true if a transition is defined, false otherwise.
		 */
		private boolean hasTransition(Assignment action) {
			for (Model m : system.getDomain().getModels()) {
				if (m.isTriggered(action.removePrimes().getVariables())) {
					return true;
				}
			}
			return false;
		}


		/**
		 * Estimates the expected value (V) of the dialogue state in the current planning
		 * horizon.
		 * 
		 * @param state the dialogue state
		 * @param horizon the planning horizon
		 * @return the expected value.
		 * @throws DialException
		 */
		private double getExpectedValue(DialogueState state, int horizon) throws DialException {

			CategoricalTable observations = getObservations(state);
			CategoricalTable nbestObs = observations.getNBest(NB_BEST_OBSERVATIONS);
			double expectedValue = 0.0;
			for (Assignment obs : nbestObs.getRows()) {
				double obsProb = nbestObs.getProb(obs);
				if (obsProb > MIN_OBSERVATION_PROB) {
					DialogueState copy = state.copy();
					addContent(copy, new CategoricalTable(obs));

					UtilityTable qValues = getQValues(copy, horizon);
					if (!qValues.getRows().isEmpty()) {
						Assignment bestAction = qValues.getBest().getKey();
						double afterObs = qValues.getUtil(bestAction);
						expectedValue += obsProb * afterObs;
					}
				}
			}	

			return expectedValue;
		}



		/**
		 * Returns the possible observations that are expected to be perceived
		 * from the dialogue state
		 * @param state the dialogue state from which to extract observations
		 * @return the inferred observations
		 * @throws DialException
		 */
		private CategoricalTable getObservations (DialogueState state) throws DialException {
			Set<String> predictionNodes = new HashSet<String>();
			for (String nodeId: state.getChanceNodeIds()) {
				if (nodeId.contains("^p")) {
					predictionNodes.add(nodeId);
				}
			}
			// intermediary observations
			for (String nodeId: new HashSet<String>(predictionNodes)) {
				if (state.getChanceNode(nodeId).hasDescendant(predictionNodes)) {
					predictionNodes.remove(nodeId);
				}
			}

			CategoricalTable modified = new CategoricalTable();
			if (!predictionNodes.isEmpty()) {
				CategoricalTable observations = state.queryProb(predictionNodes).toDiscrete();

				for (Assignment a : observations.getRows()) {
					Assignment newA = new Assignment();
					for (String var : a.getVariables()) {
						newA.addPair(var.replace("^p", ""), a.getValue(var));
					}
					modified.addRow(newA, observations.getProb(a));
				}
			}
			return modified;
		}


		/**
		 * Terminates the planning process
		 */
		@Override
		public void terminate() {
			isTerminated = true;
		}

		/**
		 * Returns true if the planner is terminated, and false otherwise.
		 */
		@Override
		public boolean isTerminated() {
			return isTerminated;
		}

	}


}
