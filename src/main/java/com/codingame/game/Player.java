package com.codingame.game;

import java.time.Duration;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

import org.dyn4j.dynamics.Body;
import org.dyn4j.dynamics.BodyFixture;
import org.dyn4j.dynamics.DetectResult;
import org.dyn4j.dynamics.contact.ContactPoint;
import org.dyn4j.geometry.AABB;
import org.dyn4j.geometry.MassType;
import org.dyn4j.geometry.Rectangle;
import org.dyn4j.geometry.Vector2;

import com.codingame.gameengine.core.AbstractMultiplayerPlayer;
import com.codingame.gameengine.module.entities.Circle;
import com.codingame.gameengine.module.entities.Group;
import com.codingame.gameengine.module.entities.Text;
import com.codingame.gameengine.module.entities.Text.FontWeight;

public class Player extends AbstractMultiplayerPlayer {
	private enum MechanicalState {
		IDLE, ACTIVATE_FRONT, TAKE,
	}

	private static final int OFFSET_W = 1610;

	private Body[] _body = { null, null };
	private Group[] _shape = { null, null };

	private double[] _width_mm = { 250, 250 };
	private double[] _height_mm = { 150, 150 };
	private Vector2[] _last_left_encoder_position = { null, null };
	private Vector2[] _last_right_encoder_position = { null, null };
	private int[] _total_left_value = { 0, 0 };
	private int[] _total_right_value = { 0, 0 };
	private Text _scoreArea;
	private boolean _isOutOfStartingArea = false;
	private boolean _fail = false;
	private Text _regularScoreArea;
	private Text _estimatedScoreArea;
	private int _estimatedScore;
	private MechanicalState[] _mechanical_state = { MechanicalState.IDLE, MechanicalState.IDLE };
	private Circle[] _graber = { null, null };
	private LinkedList<Eurobot2020Cup>[] _cupTaken = null;
	private int[] _lastPenalty = { -50000, -50000 };
	private Text _penaltiesArea;

	private int _penalties;

	int getAction(Referee referee) throws NumberFormatException, TimeoutException, ArrayIndexOutOfBoundsException {
		// Extract robot 1 and 2 set points
		int i;
		for (i = 0; i < 2; i += 1) {
			String[] line1 = this.getOutputs().get(i).split(" ");
			double left_motor = Integer.parseInt(line1[0]);
			double right_motor = Integer.parseInt(line1[1]);
			String order = line1[2];

			// clamp motors set point
			if (left_motor > 100) {
				left_motor = 100;
			}
			if (left_motor < -100) {
				left_motor = -100;
			}
			if (right_motor > 100) {
				right_motor = 100;
			}
			if (right_motor < -100) {
				right_motor = -100;
			}

			// assign motor setpoints
			double angularVelocity = (right_motor - left_motor) / 100.0 * 1;
			double velocity = (right_motor + left_motor) / 100.0 * 1;

			_body[i].setAngularVelocity(angularVelocity);
			Vector2 velocity2D = _body[i].getTransform().getRotation().rotate90().toVector(velocity);
			_body[i].setLinearVelocity(velocity2D);

			// check collision
			List<ContactPoint> cts = _body[i].getContacts(false);
			for (ContactPoint cp : cts) {
				Body b = cp.getBody1();
				if (b == _body[i]) {
					b = cp.getBody2();
				}
				if (b.getUserData() instanceof Player) {
					Player p = (Player) b.getUserData();
					if (p != this) {
						// check if we are moving foward
						Vector2 op_pos = _body[i].getTransform()
								.getInverseTransformed(b.getTransform().getTranslation());
						if (op_pos.y >= 0) {
							if (velocity <= 0) {
								continue;
							}
						} else {
							if (velocity >= 0) {
								continue;
							}
						}

						// add penalties
						int now = referee.getElapsedTime();
						int delta = now - _lastPenalty[i];
						_lastPenalty[i] = now;
						if (delta > 2000) {
							_penalties += 20;
						}
					}
				}
			}

			// Check forbidden areas
			AABB p1;
			AABB p2;

			// Génération des zones de marquage de points
			if (getIndex() == 1) {
				p1 = new AABB(0.0, 2.0 - 1.1, 0.4, 2.0 - 0.5);
				p2 = new AABB(1.65, 0, 1.95, 2.0 - 1.7);
			} else {
				p1 = new AABB(3.0 - 0.4, 2.0 - 1.1, 3.0, 2.0 - 0.5);
				p2 = new AABB(1.05, 0, 1.35, 2.0 - 1.7);
			}

			List<DetectResult> res = new LinkedList<DetectResult>();
			if (referee.getWorld().detect(p1, _body[i], true, res)) {
				deactivateAndReset(referee, "You can not be in this area");
			}
			if (referee.getWorld().detect(p2, _body[i], true, res)) {
				deactivateAndReset(referee, "You can not be in this area");
			}

			// parse mechanical order
			switch (order) {
			case "IDLE":
				// Do nothing
				break;

			case "ACTIVATE_FRONT":
				// prepare taking from front
				_mechanical_state[i] = MechanicalState.ACTIVATE_FRONT;
				break;

			case "TAKE":
				// take something
				org.dyn4j.geometry.Circle circle;
				switch (_mechanical_state[i]) {
				case ACTIVATE_FRONT:
					circle = new org.dyn4j.geometry.Circle(0.04);
					circle.translate(_body[i].getTransform().getTransformed(new Vector2(0, _height_mm[i] / 2000.0)));
					LinkedList<DetectResult> results = new LinkedList<DetectResult>();
					referee.getWorld().detect(circle, results);
					for (DetectResult r : results) {
						if (r.getBody().getUserData() instanceof Eurobot2020Cup) {
							Eurobot2020Cup cup = (Eurobot2020Cup) r.getBody().getUserData();
							take(referee, i, cup);
							break;
						}
					}
					break;

				default:
					break;
				}

				_mechanical_state[i] = MechanicalState.IDLE;
				break;

			case "RELEASE":
				// take something
				switch (_mechanical_state[i]) {
				case ACTIVATE_FRONT:
					if (_cupTaken != null) {
						if (_cupTaken[i].size() > 0) {
							Eurobot2020Cup c = _cupTaken[i].pollLast();
							c.addToTable(referee, _body[i].getTransform()
									.getTransformed(new Vector2(0, 0.08 + _height_mm[i] / 2000.0)));
						}
					}
					break;

				default:
					break;
				}

				_mechanical_state[i] = MechanicalState.IDLE;
				break;

			default:
				this.deactivate("Invalid order '" + order + "'");
				break;
			}
		}

		// Extract score data
		_estimatedScore = Integer.parseInt(this.getOutputs().get(2));

		return 0;
	}

	@SuppressWarnings("unchecked")
	private void take(Referee referee, int robot, Eurobot2020Cup cup) {
		if (_cupTaken == null) {
			_cupTaken = (LinkedList<Eurobot2020Cup>[]) new LinkedList<?>[2];
			_cupTaken[0] = new LinkedList<Eurobot2020Cup>();
			_cupTaken[1] = new LinkedList<Eurobot2020Cup>();
		}
		if (_cupTaken[robot].size() < 5) {
			double x = -0.2 - 0.1 * _cupTaken[robot].size();
			if (getIndex() != 0) {
				x = 3.0 - x;
			}
			cup.removeFromTable(referee, x, 0.5 - 0.25 * robot);
			_cupTaken[robot].add(cup);
		}
	}

	@Override
	public int getExpectedOutputLines() {
		return 3;
	}

	public Body[] getBodies() {
		return _body;
	}

	public void deactivateAndReset(Referee referee, String reason) {
		deactivate(reason);
		reset(referee);
		_fail = true;
	}

	public void reset(Referee referee) {
		for (int i = 0; i < 2; i += 1) {
			referee.getWorld().removeBody(_body[i]);
			_body[i].translateToOrigin();
			_body[i].rotate(-_body[i].getTransform().getRotationAngle());
			if (getIndex() == 0) {
				_body[i].rotate(-Math.PI / 2);
				if (i == 0) {
					_body[i].translate(0.4 - _height_mm[i] / 2000.0 - 0.03, 2.0 - (0.5 + 1.1) / 2);
				} else {
					_body[i].translate(0.0 + _height_mm[i] / 2000.0 + 0.03, 2.0 - (0.5 + 1.1) / 2);
				}

			} else {
				_body[i].rotate(Math.PI / 2);
				if (i == 0) {
					_body[i].translate(3.0 - 0.4 + _height_mm[i] / 2000.0 + 0.03, 2.0 - (0.5 + 1.1) / 2);
				} else {
					_body[i].translate(3.0 - _height_mm[i] / 2000.0 - 0.03, 2.0 - (0.5 + 1.1) / 2);
				}
			}
			_body[i].setLinearVelocity(new Vector2(0,0));
			_body[i].setAngularVelocity(0);
			referee.getWorld().addBody(_body[i]);
		}
	}

	public void createBodies(Referee referee) {
		int color;

		// Detection de la couleur
		if (getIndex() == 0) {
			color = 0x007cb0;
		} else {
			color = 0xf7b500;
		}

		for (int i = 0; i < 2; i += 1) {
			// Corps pour le moteur physique
			_body[i] = new Body();

			Rectangle shape = new Rectangle(_width_mm[i] / 1000.0, _height_mm[i] / 1000.0);

			BodyFixture fixtureBody = new BodyFixture(shape);
			fixtureBody.setDensity(200);
			fixtureBody.setRestitution(0);
			fixtureBody.setFriction(1);
			_body[i].addFixture(fixtureBody);
			_body[i].translateToOrigin();
			_body[i].setMass(MassType.NORMAL);
			_body[i].setAutoSleepingEnabled(false);
			_body[i].setBullet(true);
			_body[i].setUserData(this);

			if (getIndex() == 0) {
				_body[i].rotate(-Math.PI / 2);
				if (i == 0) {
					_body[i].translate(0.4 - _height_mm[i] / 2000.0 - 0.03, 2.0 - (0.5 + 1.1) / 2);
				} else {
					_body[i].translate(0.0 + _height_mm[i] / 2000.0 + 0.03, 2.0 - (0.5 + 1.1) / 2);
				}

			} else {
				_body[i].rotate(Math.PI / 2);
				if (i == 0) {
					_body[i].translate(3.0 - 0.4 + _height_mm[i] / 2000.0 + 0.03, 2.0 - (0.5 + 1.1) / 2);
				} else {
					_body[i].translate(3.0 - _height_mm[i] / 2000.0 - 0.03, 2.0 - (0.5 + 1.1) / 2);
				}

			}

			referee.getWorld().addBody(_body[i]);

			// Dessin sur l'interface
			com.codingame.gameengine.module.entities.Rectangle rectangle = referee.getGraphicEntityModule()
					.createRectangle();
			rectangle.setWidth((int) _width_mm[i]).setHeight((int) _height_mm[i]);
			rectangle.setLineColor(0x000000);
			rectangle.setLineWidth(2);
			rectangle.setFillColor(color);
			rectangle.setX((int) (-_width_mm[i] / 2));
			rectangle.setY((int) (-_height_mm[i] / 2));
			Text text = referee.getGraphicEntityModule().createText(String.format("%d", i + 1));
			text.setFontSize(64).setFontWeight(FontWeight.BOLD).setStrokeColor(0xFFFFFF).setFillColor(0xFFFFFF)
					.setX(-16).setY(-32);

			_graber[i] = referee.getGraphicEntityModule().createCircle();
			_graber[i].setFillColor(0xFFFFFF);
			_graber[i].setRadius(40);
			_graber[i].setY((int) (-_height_mm[i] / 2));
			_graber[i].setFillAlpha(0.5);
			_graber[i].setLineAlpha(0);
			_graber[i].setVisible(false);

			_shape[i] = referee.getGraphicEntityModule().createGroup();
			_shape[i].add(_graber[i]);
			_shape[i].add(rectangle);
			_shape[i].add(text);
		}

		// Créations des textes
		_scoreArea = referee.getGraphicEntityModule().createText("000").setFillColor(color).setStrokeColor(0xFFFFFF)
				.setFontSize(128).setFontWeight(FontWeight.BOLDER).setX(35 + getIndex() * OFFSET_W).setY(25);

		_regularScoreArea = referee.getGraphicEntityModule().createText("").setFillColor(0xFFFFFF)
				.setStrokeColor(0xFFFFFF).setFontSize(32).setX(10 + getIndex() * OFFSET_W).setY(300);
		_estimatedScoreArea = referee.getGraphicEntityModule().createText("").setFillColor(0xFFFFFF)
				.setStrokeColor(0xFFFFFF).setFontSize(32).setX(10 + getIndex() * OFFSET_W).setY(350);
		_penaltiesArea = referee.getGraphicEntityModule().createText("").setFillColor(0xFFFFFF).setStrokeColor(0xFFFFFF)
				.setFontSize(32).setX(10 + getIndex() * OFFSET_W).setY(400);
	}

	public void render(Referee referee) {

		if (!_isOutOfStartingArea) {
			// Detection si le robot est sorti !
			AABB startarea = new AABB(0 + getIndex() * (3 - 0.4), 2.0 - 1.07, 0.4 + getIndex() * (3 - 0.4),
					2.0 - 0.530);

			for (int i = 0; i < 2; i += 1) {
				if (!referee.getWorld().detect(startarea, _body[i], false, new LinkedList<DetectResult>())) {
					_isOutOfStartingArea = true;
				}
			}
		}

		// Calcul du score
		computeScore(referee);

		for (int i = 0; i < 2; i += 1) {
			// Récupération de la position en mètres et la rotation en radians
			Vector2 position = _body[i].getInitialTransform().getTranslation();
			double rotation = _body[i].getInitialTransform().getRotationAngle();

			// Converion en mm
			position.x *= 1000;
			position.y *= 1000;

			// Modification de la rotation car le repère de l'écran est indirect
			rotation = 0 - rotation;
			referee.displayShape(_shape[i], position, rotation, 1);

			org.dyn4j.geometry.Circle circle;
			// Affichage de la meca
			switch (_mechanical_state[i]) {
			case ACTIVATE_FRONT:
				_graber[i].setVisible(true);
				_graber[i].setLineWidth(0);

				circle = new org.dyn4j.geometry.Circle(0.04);
				circle.translate(_body[i].getTransform().getTransformed(new Vector2(0, _height_mm[i] / 2000.0)));
				// Recherche d'élements prenables
				LinkedList<DetectResult> results = new LinkedList<DetectResult>();
				referee.getWorld().detect(circle, results);
				for (DetectResult r : results) {
					if (r.getBody().getUserData() instanceof Eurobot2020Cup) {
						_graber[i].setLineColor(0);
						_graber[i].setLineWidth(16);
						break;
					}
				}
				break;

			default:
			case IDLE:
				_graber[i].setVisible(false);
				break;
			}
		}
	}

	private void computeScore(Referee referee) {
		int score = 0;
		int classical_score = 0;

		if (_isOutOfStartingArea  && !_fail) {
			score = 5;

			AABB p1;
			AABB p2;
			AABB p1g;
			AABB p1r;
			AABB p2g;
			AABB p2r;

			// Génération des zones de marquage de points
			if (getIndex() == 0) {
				p1 = new AABB(0.0, 2.0 - 1.1, 0.4, 2.0 - 0.5);
				p2 = new AABB(1.65, 0, 1.95, 2.0 - 1.7);

				p1g = new AABB(0.0, 2.0 - 0.53, 0.4, 2 - 0.50);
				p1r = new AABB(0.0, 2.0 - 1.1, 0.4, 2 - 1.07);

				p2g = new AABB(1.65, 0, 1.75, 2 - 1.7);
				p2r = new AABB(1.85, 0, 1.95, 2 - 1.7);
			} else {
				p1 = new AABB(3.0 - 0.4, 2.0 - 1.1, 3.0, 2.0 - 0.5);
				p2 = new AABB(1.05, 0, 1.35, 2.0 - 1.7);

				p1r = new AABB(3 - 0.4, 2.0 - 0.53, 3.0, 2 - 0.50);
				p1g = new AABB(3 - 0.4, 2.0 - 1.1, 3.0, 2 - 1.07);

				p2g = new AABB(1.05, 0, 1.15, 2 - 1.7);
				p2r = new AABB(1.25, 0, 1.35, 2 - 1.7);
			}

			LinkedList<DetectResult> results = new LinkedList<DetectResult>();
			referee.getWorld().detect(p1, results);
			referee.getWorld().detect(p2, results);
			for (DetectResult r : results) {
				if (r.getBody().getUserData() instanceof Eurobot2020Cup) {
					classical_score += 1;
				}
			}

			// Vérification cannaux port 1
			results.clear();
			int green = 0;
			referee.getWorld().detect(p1g, results);
			for (DetectResult r : results) {
				if (r.getBody().getUserData() instanceof Eurobot2020Cup) {
					Eurobot2020Cup c = (Eurobot2020Cup) r.getBody().getUserData();
					if (c.getType() == Eurobot2020CupType.GREEN) {
						green += 1;
					}
				}
			}

			results.clear();
			int red = 0;
			referee.getWorld().detect(p1r, results);
			for (DetectResult r : results) {
				if (r.getBody().getUserData() instanceof Eurobot2020Cup) {
					Eurobot2020Cup c = (Eurobot2020Cup) r.getBody().getUserData();
					if (c.getType() == Eurobot2020CupType.RED) {
						red += 1;
					}
				}
			}

			classical_score += red + green + 2 * Integer.min(red, green);

			// Vérification cannaux port 2
			results.clear();
			green = 0;
			referee.getWorld().detect(p2g, results);
			for (DetectResult r : results) {
				if (r.getBody().getUserData() instanceof Eurobot2020Cup) {
					Eurobot2020Cup c = (Eurobot2020Cup) r.getBody().getUserData();
					if (c.getType() == Eurobot2020CupType.GREEN) {
						green += 1;
					}
				}
			}

			results.clear();
			red = 0;
			referee.getWorld().detect(p2r, results);
			for (DetectResult r : results) {
				if (r.getBody().getUserData() instanceof Eurobot2020Cup) {
					Eurobot2020Cup c = (Eurobot2020Cup) r.getBody().getUserData();
					if (c.getType() == Eurobot2020CupType.RED) {
						red += 1;
					}
				}
			}

			classical_score += red + green + 2 * Integer.min(red, green);

			int bonus = (int) (Math.ceil(0.3 * classical_score) - Math.abs(_estimatedScore - classical_score));
			if (bonus < 0) {
				bonus = 0;
			}
			score += bonus;

			score += classical_score;
			score -= _penalties;

			if (score < 0) {
				score = 0;
			}
		}

		_regularScoreArea.setText(String.format("Regular points: %d", classical_score));
		_estimatedScoreArea.setText(String.format("Est. points: %d", _estimatedScore));
		_penaltiesArea.setText(String.format("Penalties: %d", -_penalties));
		_scoreArea.setText(String.format("%03d", getScore()));
		setScore(score);
	}

	public void sendPlayerInputs() {
		for (int i = 0; i < 2; i += 1) {
			Vector2 left_encoder_position = _body[i].getTransform()
					.getTransformed(new Vector2(-_width_mm[i] / 2000.0, 0));
			Vector2 right_encoder_position = _body[i].getTransform()
					.getTransformed(new Vector2(_width_mm[i] / 2000.0, 0));

			// calcul de l'encodeur gauche
			int left_value;
			if (_last_left_encoder_position[i] == null) {
				left_value = 0;
			} else {
				left_value = (int) (left_encoder_position.distance(_last_left_encoder_position[i]) * 10000);
				if (_body[i].getTransform().getInverseTransformed(_last_left_encoder_position[i]).y >= 0) {
					left_value = -left_value;
				}
			}

			_last_left_encoder_position[i] = left_encoder_position;

			// calcul de l'encodeur droit
			int right_value;
			if (_last_right_encoder_position[i] == null) {
				right_value = 0;
			} else {
				right_value = (int) (right_encoder_position.distance(_last_right_encoder_position[i]) * 10000);
				if (_body[i].getTransform().getInverseTransformed(_last_right_encoder_position[i]).y >= 0) {
					right_value = -right_value;
				}
			}
			_last_right_encoder_position[i] = right_encoder_position;

			// Ajout a l'intégrateur
			_total_left_value[i] += left_value;
			_total_right_value[i] += right_value;

			String last_taken = "?";
			if (_cupTaken != null) {
				if (_cupTaken[i].size() > 0) {
					Eurobot2020Cup c = _cupTaken[i].peekLast();
					if (c.getType() == Eurobot2020CupType.GREEN) {
						last_taken = "GREEN";
					} else {
						last_taken = "RED";
					}
				}
			}

			sendInputLine(_total_left_value[i] + " " + _total_right_value[i] + " " + last_taken);
		}
		execute();
	}

	public void sendGameConfiguration() {
		// send player color
		String color = "BLUE";
		if (getIndex() == 1) {
			color = "YELLOW";
		}

		sendInputLine(color);
	}
}
