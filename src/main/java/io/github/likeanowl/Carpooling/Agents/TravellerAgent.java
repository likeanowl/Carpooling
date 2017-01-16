package io.github.likeanowl.Carpooling.Agents;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.*;
import jade.core.behaviours.ReceiverBehaviour.Handle;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import org.jgrapht.alg.DijkstraShortestPath;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.SimpleWeightedGraph;

import java.util.HashSet;
import java.util.Set;

import static io.github.likeanowl.Carpooling.Constants.Constants.*;

public class TravellerAgent extends Agent {
	private String travellerCategory = NOT_SET;
	private int cyclesCount = 0;
	private String targetPlace;
	private String currentPlace;
	private Set<AID> drivers = new HashSet<>();
	private int distance;
	private SimpleWeightedGraph<String, DefaultWeightedEdge> roads = new SimpleWeightedGraph<>(DefaultWeightedEdge.class);
	private double maxEconomy = 0;
	private AID coDriver;
	private int coDriversCount = 0;

	@Override
	protected void setup() {
		//Setting roadmap
		initRoadmap();
		Object[] args = getArguments();
		if (args != null && args.length >= 2) {
			currentPlace = (String) args[0];
			targetPlace = (String) args[1];
			distance = (int) (new DijkstraShortestPath<>(roads, currentPlace, targetPlace)).getPathLength();
			String agentState = "Traveler agent: " + getAID().getLocalName() + "\n" + "Current place: " + currentPlace
					+ "; Target place: " + targetPlace + "; Distance: " + distance;
			System.out.println(agentState);
			DFAgentDescription agentDescription = new DFAgentDescription();
			agentDescription.setName(getAID());
			ServiceDescription serviceDescription = new ServiceDescription();
			serviceDescription.setType(CARPOOLING);
			serviceDescription.setName("Carpooling");
			agentDescription.addServices(serviceDescription);
			try {
				DFService.register(this, agentDescription);
			} catch (FIPAException fe) {
				fe.printStackTrace();
			}
			addBehaviour(new WakerBehaviour(this, TIMEOUT) {
				@Override
				protected void onWake() {
					addBehaviour(new LifeCycle(myAgent));
				}
			});
		}
	}

	private void initRoadmap() {
		roads.addVertex("Moscow");
		roads.addVertex("SaintPetersburg");
		roads.addVertex("Kazan");
		roads.addVertex("Chelyabinsk");
		roads.addVertex("Ekaterinburg");
		roads.addVertex("Yaroslavl");
		roads.setEdgeWeight(roads.addEdge("Moscow", "SaintPetersburg"), 600);
		roads.setEdgeWeight(roads.addEdge("Moscow", "Kazan"), 500);
		roads.setEdgeWeight(roads.addEdge("Moscow", "Ekaterinburg"), 2800);
		roads.setEdgeWeight(roads.addEdge("Moscow", "Chelyabinsk"), 600);
		roads.setEdgeWeight(roads.addEdge("Chelyabinsk", "Kazan"), 150);
		roads.setEdgeWeight(roads.addEdge("Yaroslavl", "SaintPetersburg"), 1200);
		roads.setEdgeWeight(roads.addEdge("Yaroslavl", "Moscow"), 300);
		roads.setEdgeWeight(roads.addEdge("Kazan", "Yaroslavl"), 600);
	}

	/**
	 * Just lifecycle of agent
	 */
	private class LifeCycle extends SequentialBehaviour {
		public LifeCycle(final Agent agent) {
			super(agent);
			CycleLogic cycleLogic = new CycleLogic(agent, this);
			addBehaviour(cycleLogic);
		}
	}

	/**
	 * Inner lifecycle logic
	 */
	private class CycleLogic extends OneShotBehaviour {
		private Agent agent;
		private LifeCycle cycle;

		public CycleLogic(Agent agent, LifeCycle cycle) {
			this.agent = agent;
			this.cycle = cycle;
		}

		@Override
		public void action() {
			if (cyclesCount < MAX_CYCLES_COUNT) {
				cyclesCount++;
				drivers = new HashSet<>();
				coDriver = null;
				maxEconomy = 0;
				DFAgentDescription template = new DFAgentDescription();
				ServiceDescription serviceDescription = new ServiceDescription();
				serviceDescription.setType(CARPOOLING);
				template.addServices(serviceDescription);
				try {
					DFAgentDescription[] result = DFService.search(agent, template);
					for (DFAgentDescription res : result) {
						if (!res.getName().equals(agent.getAID()))
							drivers.add(res.getName());
					}
				} catch (FIPAException fe) {
					fe.printStackTrace();
				}
				if (drivers.size() > 0) {
					ParallelBehaviour parallelBehaviour = new ParallelBehaviour(agent, ParallelBehaviour.WHEN_ALL);
					parallelBehaviour.addSubBehaviour(new DriverQuerier(agent));
					parallelBehaviour.addSubBehaviour(new PassengerSearcher(agent));
					cycle.addSubBehaviour(parallelBehaviour);
					cycle.addSubBehaviour(new OfferRequestServer(agent));
				} else {
					addBehaviour(new WakerBehaviour(agent, TIMEOUT1) {
						@Override
						protected void onWake() {
							System.out.println("restart");
							addBehaviour(new LifeCycle(myAgent));
						}
					});
				}
			} else {
				if (coDriversCount == 0)
					System.out.println(ANSI_RED + agent.getLocalName() + ": goes alone" + ANSI_RESET);
				else
					System.out.println(ANSI_RED + agent.getLocalName() + ": goes with " + coDriversCount + " agent(s)" +
							" " +
									"on board" + ANSI_RESET);
				try {
					DFService.deregister(agent);
				} catch (FIPAException fe) {
					fe.printStackTrace();
				}
				doDelete();
			}
		}
	}

	private class DriverQuerier extends SequentialBehaviour {
		private String replyWith;

		public DriverQuerier(Agent agent) {
			super(agent);
			ProposeSender proposeSender = new ProposeSender(agent);
			addSubBehaviour(proposeSender); // send proposals for drivers
			replyWith = proposeSender.getReplyWith();
			ParallelBehaviour parallelBehaviour = new ParallelBehaviour(agent, ParallelBehaviour.WHEN_ALL);
			for (AID dr : drivers) {
				parallelBehaviour.addSubBehaviour(new ProposeReplyReceiver(agent, dr, replyWith));
				//add Receiver for all proposals
			}
			addSubBehaviour(parallelBehaviour); // receive replies from drivers
		}
	}

	private class ProposeSender extends OneShotBehaviour {
		private String replyWith;

		public ProposeSender(Agent agent) {
			super(agent);
			replyWith = String.valueOf(System.currentTimeMillis());
		}

		public void action() {
			ACLMessage msg = new ACLMessage(ACLMessage.CFP);
			msg.removeReceiver(getAID());
			msg.setContent(currentPlace + " " + targetPlace + " " + distance + " " + travellerCategory);
			msg.setConversationId(CARPOOLING);
			msg.setReplyWith(replyWith);
			System.out.println(myAgent.getLocalName() + ": call for proposals");
			for (AID dr : drivers) {
				msg.addReceiver(dr);
			}
			myAgent.send(msg);
		}

		public String getReplyWith() {
			return replyWith;
		}
	}

	private class ProposeReplyReceiver extends SequentialBehaviour {
		public ProposeReplyReceiver(final Agent agent, final AID sender, String replyWith) {
			final Handle handle = ReceiverBehaviour.newHandle();
			addSubBehaviour(new ReceiverBehaviour(agent, handle, MILLIS, MessageTemplate.and(MessageTemplate.MatchSender(sender),
					MessageTemplate.and(MessageTemplate.MatchConversationId(CARPOOLING), MessageTemplate.MatchInReplyTo(replyWith)))));
			addSubBehaviour(new CategoryReplier(agent, handle, sender));
		}
	}

	private class CategoryReplier extends OneShotBehaviour {
		private Handle handle;
		private Agent agent;
		private AID sender;

		public CategoryReplier(Agent agent, Handle handle, AID sender) {
			this.agent = agent;
			this.handle = handle;
			this.sender = sender;
		}

		@Override
		public void action() {
			try {
				ACLMessage msg = handle.getMessage();
				if (msg.getPerformative() == ACLMessage.PROPOSE) {
					int price = Integer.parseInt(msg.getContent());
					if (distance - price > maxEconomy) {
						maxEconomy = distance - price;
						coDriver = msg.getSender();
						travellerCategory = PASSENGER;
					}
				}
			} catch (ReceiverBehaviour.TimedOut timedOut) {
				System.out.println(agent.getLocalName() + ": time out while receiving message from " + sender
						.getLocalName());
			} catch (ReceiverBehaviour.NotYetReady notYetReady) {
				System.out.println(agent.getLocalName() + ": message from " + sender.getLocalName() + " not yet " +
						"ready");
			}
		}
	}

	private class PassengerSearcher extends ParallelBehaviour {
		public PassengerSearcher(Agent agent) {
			super(agent, ParallelBehaviour.WHEN_ALL);
			for (AID dr : drivers) {
				addSubBehaviour(new ProposeReplier(agent, dr));
			}
			//Reply to all proposals
		}
	}

	private class ProposeReplier extends SequentialBehaviour {
		public ProposeReplier(final Agent agent, final AID sender) {
			final Handle handle = ReceiverBehaviour.newHandle();
			addSubBehaviour(new ReceiverBehaviour(agent, handle, MILLIS1
					, MessageTemplate.and(MessageTemplate.MatchPerformative(ACLMessage.CFP)
					, MessageTemplate.MatchSender(sender))));
			ProposeLogic proposeLogic = new ProposeLogic(handle, agent, sender);
			addSubBehaviour(proposeLogic);
		}
	}

	private class ProposeLogic extends OneShotBehaviour {
		private Handle handle;
		private AID sender;
		private Agent agent;

		public ProposeLogic(Handle handle, Agent agent, AID sender) {
			this.handle = handle;
			this.agent = agent;
			this.sender = sender;

		}

		@Override
		public void action() {
			try {
				ACLMessage msg = handle.getMessage();
				AID aid = msg.getSender();
				ACLMessage reply = msg.createReply();
				String[] content = msg.getContent().split(" ");
				int passengersDistance = Integer.parseInt(content[2]);
				if (passengersDistance <= distance) {
					int extraDistance = ((int) new DijkstraShortestPath<>(roads, currentPlace, content[0]).getPathLength() +
							(int) new DijkstraShortestPath<>(roads, content[1], targetPlace).getPathLength() + passengersDistance) - distance;
					int price = (int) (0.5 * (extraDistance + passengersDistance));
					if (0.5 * (passengersDistance - extraDistance) > maxEconomy) {
						maxEconomy = (int) (0.5 * (passengersDistance - extraDistance));
						coDriver = msg.getSender();
						System.out.println(coDriver.getLocalName());
						travellerCategory = DRIVER;
						if (passengersDistance == distance) {
							if (content[3].equals(NOT_SET) || content[3].equals(PASSENGER))
								travellerCategory = DRIVER;
							else if (content[3].equals(DRIVER))
								travellerCategory = PASSENGER;
						}
					}
					reply.setPerformative(ACLMessage.PROPOSE);
					System.out.println(myAgent.getLocalName() + ": send propose to "
							+ msg.getSender().getLocalName() + "; price: " + price);
					reply.setContent(Integer.toString(price));
				} else {
					reply.setPerformative(ACLMessage.DISCONFIRM);
					System.out.println(myAgent.getLocalName() + ": way to " + msg.getSender().getLocalName()
							+ " is too long");
				}
				myAgent.send(reply);
			} catch (ReceiverBehaviour.TimedOut timedOut) {
				System.out.println(agent.getLocalName() + ": time out while receiving cfp from " + sender.getLocalName());
			} catch (ReceiverBehaviour.NotYetReady notYetReady) {
				System.out.println(agent.getLocalName() + ": cfp from " + sender.getLocalName() + " not yet ready");
			}
		}
	}

	private class OfferRequestServer extends SequentialBehaviour {
		public OfferRequestServer(Agent agent) {
			super(agent);
		}

		@Override
		public void onStart() {
			super.onStart();
			if (coDriver != null) {
				final String coDriverName = coDriver.getLocalName();
				addSubBehaviour(new AgreeForBestOffer(myAgent)); //send AGREE to coDriver
				ParallelBehaviour parallelBehaviour = new ParallelBehaviour(ParallelBehaviour.WHEN_ANY);
				SequentialBehaviour sequentialBehaviour = new SequentialBehaviour(myAgent);
				final Handle handle = ReceiverBehaviour.newHandle();
				sequentialBehaviour.addSubBehaviour(new ReceiverBehaviour(myAgent, handle, MILLIS2
						, MessageTemplate.and(MessageTemplate.MatchConversationId(AGREE_FOR_CARPOOLING)
						, MessageTemplate.MatchSender(coDriver))));
				ReplyDecider replyDecider = new ReplyDecider(myAgent, handle, coDriverName);
				sequentialBehaviour.addSubBehaviour(replyDecider);
				parallelBehaviour.addSubBehaviour(sequentialBehaviour);//receive AGREE or REFUSE from coDriver
				parallelBehaviour.addSubBehaviour(new Refuser(myAgent));//REFUSE offers from other travelers
				addSubBehaviour(parallelBehaviour);
			} else {
				addBehaviour(new Restarter(myAgent, 5000));
			}
		}
	}

	private class ReplyDecider extends OneShotBehaviour {
		private Handle handle;
		private String coDriverName;

		public ReplyDecider(Agent agent, Handle handle, String coDriverName) {
			super(agent);
			this.handle = handle;
			this.coDriverName = coDriverName;
		}

		@Override
		public void action() {
			try {
				ACLMessage msg = handle.getMessage();
				if (msg.getPerformative() == ACLMessage.AGREE) {
					System.out.println(ANSI_RED + myAgent.getLocalName() + ": goes with "
							+ coDriverName + " as " + travellerCategory + ANSI_RESET);
					coDriversCount++;
					if (coDriversCount == 3 || travellerCategory.equals(PASSENGER)) {
						ParallelBehaviour offer = new ParallelBehaviour(myAgent, 2);
						offer.addSubBehaviour(new Refuser(myAgent));
						offer.addSubBehaviour(new Deregister(myAgent));
						offer.addSubBehaviour(new WakerBehaviour(myAgent, 5000) {
							@Override
							protected void onWake() {
								doDelete();
							}
						});
						addBehaviour(offer);
					} else {
						addBehaviour(new Restarter(myAgent, 5000));
					}
				} else if (travellerCategory.equals(PASSENGER)){
					addBehaviour(new Restarter(myAgent, 5000));
				} else {
					addBehaviour(new Deregister(myAgent));
				}
			} catch (ReceiverBehaviour.NotYetReady notYetReady) {
				System.out.println(myAgent.getLocalName() + ": reply not yet ready");
				addBehaviour(new Restarter(myAgent, 3000));
			} catch (ReceiverBehaviour.TimedOut timedOut) {
				System.out.println(myAgent.getLocalName() + ": time out");
				addBehaviour(new Restarter(myAgent, 3000));
			}
		}
	}

	private class Restarter extends WakerBehaviour {
		public Restarter(Agent a, long timeout) {
			super(a, timeout);
		}

		@Override
		protected void onWake() {
			System.out.println(myAgent.getLocalName() + ": restart life cycle");
			addBehaviour(new LifeCycle(myAgent));
		}
	}

	private class Refuser extends CyclicBehaviour {
		Refuser(Agent agent) {
			super(agent);
		}

		@Override
		public void action() {
			ACLMessage agr = myAgent.receive(MessageTemplate.and(MessageTemplate.not(MessageTemplate
							.MatchSender(coDriver)), MessageTemplate.MatchPerformative(ACLMessage.AGREE)));
			if (agr != null) {
				ACLMessage rfs = agr.createReply();
				rfs.setPerformative(ACLMessage.REFUSE);
				myAgent.send(rfs);
				System.out.println(myAgent.getLocalName() + ": refuse agree from " + agr.getSender().getLocalName());
			} else {
				block();
			}
		}
	}

	private class AgreeForBestOffer extends OneShotBehaviour {
		AgreeForBestOffer(Agent agent) {
			super(agent);
		}

		@Override
		public void action() {
			if (coDriver != null) {
				ACLMessage msg = new ACLMessage(ACLMessage.AGREE);
				msg.addReceiver(coDriver);
				msg.setConversationId(AGREE_FOR_CARPOOLING);
				myAgent.send(msg);
				System.out.println(myAgent.getLocalName() + ": want go with " + coDriver.getLocalName()
						+ "; with economy " + (int) maxEconomy);
			}
		}
	}

	private class Deregister extends OneShotBehaviour {
		public Deregister (Agent agent) {
			super(agent);
		}

		@Override
		public void action() {
			try {
				DFService.deregister(myAgent);
			} catch (FIPAException fe) {
				fe.printStackTrace();
			}
		}
	}
}