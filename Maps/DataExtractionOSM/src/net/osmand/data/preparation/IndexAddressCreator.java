package net.osmand.data.preparation;

import gnu.trove.map.hash.TLongObjectHashMap;
import gnu.trove.set.hash.TLongHashSet;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import net.osmand.Algoritms;
import net.osmand.IProgress;
import net.osmand.data.Boundary;
import net.osmand.data.Building;
import net.osmand.data.City;
import net.osmand.data.City.CityType;
import net.osmand.data.DataTileManager;
import net.osmand.data.Street;
import net.osmand.data.WayBoundary;
import net.osmand.data.preparation.DBStreetDAO.SimpleStreet;
import net.osmand.osm.Entity;
import net.osmand.osm.Entity.EntityId;
import net.osmand.osm.LatLon;
import net.osmand.osm.MapUtils;
import net.osmand.osm.Node;
import net.osmand.osm.OSMSettings.OSMTagKey;
import net.osmand.osm.Relation;
import net.osmand.osm.Way;
import net.osmand.swing.Messages;
import net.sf.junidecode.Junidecode;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class IndexAddressCreator extends AbstractIndexPartCreator{
	
	private static final Log log = LogFactory.getLog(IndexAddressCreator.class);
	private final Log logMapDataWarn;

	private PreparedStatement addressCityStat;

	// MEMORY address : choose what to use ?
	private boolean loadInMemory = true;

	// MEMORY address : address structure
	// load it in memory
	private Map<EntityId, City> cities = new LinkedHashMap<EntityId, City>();
	private DataTileManager<City> cityVillageManager = new DataTileManager<City>(13);
	private DataTileManager<City> cityManager = new DataTileManager<City>(10);
	private List<Relation> postalCodeRelations = new ArrayList<Relation>();
	private Map<City, Boundary> cityBoundaries = new HashMap<City, Boundary>();
	private Map<Boundary,List<City>> boundariesToCities = new HashMap<Boundary,List<City>>();
	private Set<Boundary> allBoundaries = new HashSet<Boundary>();
	private TLongHashSet visitedBoundaryWays = new TLongHashSet();
	
	private boolean normalizeStreets; 
	private String[] normalizeDefaultSuffixes;
	private String[] normalizeSuffixes;
	
	private boolean saveAddressWays;
	
	// TODO
	Connection mapConnection;
	DBStreetDAO streetDAO;

	

	public static class StreetAndDistrict {
		private final Street street;
		private final String district;

		StreetAndDistrict(Street street, String district) {
			this.street = street;
			this.district = district;
		}

		public Street getStreet() {
			return street;
		}

		public String getDistrict() {
			return district;
		}
	}
	
	public IndexAddressCreator(Log logMapDataWarn){
		this.logMapDataWarn = logMapDataWarn;
		streetDAO = loadInMemory ? new CachedDBStreetDAO() : new DBStreetDAO();
	}
	
	
	public void initSettings(boolean normalizeStreets, String[] normalizeDefaultSuffixes, String[] normalizeSuffixes,
			boolean saveAddressWays, String cityAdminLevel) {
		cities.clear();
		cityManager.clear();
		postalCodeRelations.clear();
		cityBoundaries.clear();
		this.normalizeStreets = normalizeStreets;
		this.normalizeDefaultSuffixes = normalizeDefaultSuffixes;
		this.normalizeSuffixes = normalizeSuffixes;
		this.saveAddressWays = saveAddressWays;
	}

	public void registerCityIfNeeded(Entity e) {
		if (e instanceof Node && e.getTag(OSMTagKey.PLACE) != null) {
			City city = new City((Node) e);
			if (city.getType() != null && !Algoritms.isEmpty(city.getName())) {
				if (city.getType() == CityType.CITY || city.getType() == CityType.TOWN) {
					cityManager.registerObject(((Node) e).getLatitude(), ((Node) e).getLongitude(), city);
				} else {
					cityVillageManager.registerObject(((Node) e).getLatitude(), ((Node) e).getLongitude(), city);
				}
				cities.put(city.getEntityId(), city);
			}
		}
	}
	
	public void indexBoundariesRelation(Entity e, OsmDbAccessorContext ctx) throws SQLException {
		Boundary boundary = extractBoundary(e, ctx);
		if (boundary != null && boundary.isClosedWay() && boundary.getAdminLevel() >= 4 && boundary.getCenterPoint() != null && !Algoritms.isEmpty(boundary.getName())) {
			LatLon boundaryCenter = boundary.getCenterPoint();
			List<City> citiesToSearch = new ArrayList<City>();
			citiesToSearch.addAll(cityManager.getClosestObjects(boundaryCenter.getLatitude(), boundaryCenter.getLongitude(), 3));
			citiesToSearch.addAll(cityVillageManager.getClosestObjects(boundaryCenter.getLatitude(), boundaryCenter.getLongitude(), 3));

			City cityFound = null;
			String boundaryName = boundary.getName().toLowerCase();
			for (City c : citiesToSearch) {
				if (boundary.containsPoint(c.getLocation())) {
					if (boundaryName.equalsIgnoreCase(c.getName())) {
						cityFound = c;
						break;
					}
				}
			}
			//We should not look for similarities, this can be 'very' wrong....
			if (cityFound == null) {
				// try to find same name in the middle
				for (City c : citiesToSearch) {
					if (boundary.containsPoint(c.getLocation())) {
						String lower = c.getName().toLowerCase();
						if (boundaryName.startsWith(lower + " ") || boundaryName.endsWith(lower + " ") || boundaryName.contains(" " + lower + " ")) {
							cityFound = c;
							break;
						}
					}
				}
			}
			if (cityFound != null) {
				putCityBoundary(boundary, cityFound);
			}
			allBoundaries.add(boundary);
		} else if (boundary != null && !boundary.isClosedWay()){
			if(logMapDataWarn != null) {
				logMapDataWarn.warn("Not using opened boundary: " + boundary);
			} else {
				log.info("Not using opened boundary: " + boundary);
			}
		}
	}

	public void bindCitiesWithBoundaries(IProgress progress) {
		progress.startWork(cities.size()*2);
		Set<Boundary> freeBoundaries = new HashSet<Boundary>(allBoundaries);
		freeBoundaries.removeAll(cityBoundaries.values());
		//for cities without boundaries, try to find the right one
		for (City c : cities.values()) {
			progress.progress(1);
			Boundary cityB = cityBoundaries.get(c);
			int smallestAdminLevel = 8; //TODO start at level 8 for now...
			if (cityB == null) {
				LatLon location = c.getLocation();
				Boundary smallestBoundary = null;
				//try to found boundary
				for (Boundary b : freeBoundaries) {
					if (b.getAdminLevel() > smallestAdminLevel) {
						if (b.containsPoint(location.getLatitude(), location.getLongitude())) {
							//the bigger the admin level, the smaller the boundary :-)
								smallestAdminLevel = b.getAdminLevel();
								smallestBoundary = b;
						}
					}
				}
				if (smallestBoundary != null) {
					Boundary oldBoundary = putCityBoundary(smallestBoundary,c);
					freeBoundaries.remove(smallestBoundary);
					if (oldBoundary != null) {
						freeBoundaries.add(oldBoundary);
					}
				}
			}
		}
		
		//now for each city, try to put it in all boundaries it belongs to
		for (City c : cities.values()) {
			progress.progress(1);
			for (Boundary b : allBoundaries) {
				if (b.containsPoint(c.getLocation())) {
					List<City> list = boundariesToCities.get(b);
					if (list == null) {
						list = new ArrayList<City>(1);
						boundariesToCities.put(b, list);
					}
					list.add(c);
				}
			}
		}
	}

	private int extractBoundaryAdminLevel(Entity e) {
		int adminLevel = -1;
		try {
			String tag = e.getTag(OSMTagKey.ADMIN_LEVEL);
			if (tag == null) {
				return adminLevel;
			}
			return Integer.parseInt(tag);
		} catch (NumberFormatException ex) {
			return adminLevel;
		}
	}

	private Boundary putCityBoundary(Boundary boundary, City cityFound) {
		final Boundary oldBoundary = cityBoundaries.get(cityFound);
		if (oldBoundary != null) {
			// try to found the biggest area (not small center district)
			if (oldBoundary.getAdminLevel() > boundary.getAdminLevel() && !oldBoundary.getName().equalsIgnoreCase(cityFound.getName())) {
				cityBoundaries.put(cityFound, boundary);
				String s = "City: " + cityFound.getName() + " boundary: " + boundary.getName();
				if(logMapDataWarn != null) {
					logMapDataWarn.info(s);
				} else {
					log.info(s);
				}
			} else if(boundary.getName().equalsIgnoreCase(cityFound.getName()) && 
					!oldBoundary.getName().equalsIgnoreCase(cityFound.getName())){
				cityBoundaries.put(cityFound, boundary);
				String s = "City: " + cityFound.getName() + " boundary: " + boundary.getName();
				if(logMapDataWarn != null) {
					logMapDataWarn.info(s);
				} else {
					log.info(s);
				}
			} else if(oldBoundary.getAdminLevel() == boundary.getAdminLevel() && 
					oldBoundary != boundary && boundary.getName().equalsIgnoreCase(oldBoundary.getName())){
				if(!oldBoundary.isClosedWay() && !boundary.isClosedWay()){
					oldBoundary.getInnerWays().addAll(boundary.getInnerWays());
					oldBoundary.getOuterWays().addAll(boundary.getOuterWays());
				}
			}
		} else {
			cityBoundaries.put(cityFound, boundary);
			if(logMapDataWarn != null) {
				logMapDataWarn.info("City: " + cityFound.getName() + " boundary: " + boundary.getName());
			} else {
				log.info("City: " + cityFound.getName() + " boundary: " + boundary.getName());
			}
		}
		return oldBoundary;
	}

	private boolean isBoundary(Entity e) {
		return "administrative".equals(e.getTag(OSMTagKey.BOUNDARY)) && (e instanceof Relation || e instanceof Way);
	}
	
	private Boundary extractBoundary(Entity e, OsmDbAccessorContext ctx) throws SQLException {
		if (isBoundary(e)) {
			Boundary boundary = null;
			if (e instanceof Relation) {
				Relation aRelation = (Relation) e;
				ctx.loadEntityData(aRelation);
				boundary = new Boundary(true); //is computed later
				boundary.setName(aRelation.getTag(OSMTagKey.NAME));
				boundary.setBoundaryId(aRelation.getId());
				boundary.setAdminLevel(extractBoundaryAdminLevel(aRelation));
				Map<Entity, String> entities = aRelation.getMemberEntities();
				for (Entity es : entities.keySet()) {
					if (es instanceof Way) {
						boolean inner = "inner".equals(entities.get(es)); //$NON-NLS-1$
						if (inner) {
							boundary.getInnerWays().add((Way) es);
						} else {
							String wName = es.getTag(OSMTagKey.NAME);
							// if name are not equal keep the way for further check (it could be different suburb)
							if (Algoritms.objectEquals(wName, boundary.getName()) || wName == null) {
								visitedBoundaryWays.add(es.getId());
							}
							boundary.getOuterWays().add((Way) es);
						}
					}
				}
				boundary.computeIsClosedWay();
			} else if (e instanceof Way) {
				if (!visitedBoundaryWays.contains(e.getId())) {
					List<Long> nodeIds = ((Way) e).getNodeIds();
					boolean closed = false;
					if(nodeIds.size() > 0){
						closed = Algoritms.objectEquals(nodeIds.get(0), nodeIds.get(nodeIds.size() - 1));
					}
					boundary = new WayBoundary(closed);
					boundary.setName(e.getTag(OSMTagKey.NAME));
					boundary.setBoundaryId(e.getId());
					boundary.setAdminLevel(extractBoundaryAdminLevel(e));
					boundary.getOuterWays().add((Way) e);
				}
			}
			return boundary;
		} else {
			return null;
		}
	}
	
	public void indexAddressRelation(Relation i, OsmDbAccessorContext ctx) throws SQLException {
		if (i instanceof Relation && "address".equals(i.getTag(OSMTagKey.TYPE))) { //$NON-NLS-1$
			String type = i.getTag(OSMTagKey.ADDRESS_TYPE);
			boolean house = "house".equals(type); //$NON-NLS-1$
			boolean street = "a6".equals(type); //$NON-NLS-1$
			if (house || street) {
				// try to find appropriate city/street
				City c = null;
				// load with member ways with their nodes and tags !
				ctx.loadEntityData(i);

				Collection<Entity> members = i.getMembers("is_in"); //$NON-NLS-1$
				Relation a3 = null;
				Relation a6 = null;
				if (!members.isEmpty()) {
					if (street) {
						a6 = i;
					}
					Entity in = members.iterator().next();
					ctx.loadEntityData(in);
					if (in instanceof Relation) {
						// go one level up for house
						if (house) {
							a6 = (Relation) in;
							members = ((Relation) in).getMembers("is_in"); //$NON-NLS-1$
							if (!members.isEmpty()) {
								in = members.iterator().next();
								ctx.loadEntityData(in);
								if (in instanceof Relation) {
									a3 = (Relation) in;
								}
							}

						} else {
							a3 = (Relation) in;
						}
					}
				}

				if (a3 != null) {
					Collection<EntityId> memberIds = a3.getMemberIds("label"); //$NON-NLS-1$
					if (!memberIds.isEmpty()) {
						c = cities.get(memberIds.iterator().next());
					}
				}
				if (c != null && a6 != null) {
					String name = a6.getTag(OSMTagKey.NAME);

					if (name != null) {
						LatLon location = c.getLocation();
						for (Entity e : i.getMembers(null)) {
							if (e instanceof Way) {
								LatLon l = ((Way) e).getLatLon();
								if (l != null) {
									location = l;
									break;
								}
							}
						}

						Set<Long> streetId = registerStreetInCities(name, null, location, Collections.singletonList(c));
						if (streetId == null) {
							return;
						}
						if (street) {
							for (Map.Entry<Entity, String> r : i.getMemberEntities().entrySet()) {
								if ("street".equals(r.getValue())) { //$NON-NLS-1$
									if (r.getKey() instanceof Way && saveAddressWays) {
										streetDAO.writeStreetWayNodes(streetId, (Way) r.getKey());
									}
								} else if ("house".equals(r.getValue())) { //$NON-NLS-1$
									// will be registered further in other case
									if (!(r.getKey() instanceof Relation)) {
										String hno = r.getKey().getTag(OSMTagKey.ADDR_HOUSE_NUMBER);
										if (hno != null) {
											Building building = new Building(r.getKey());
											building.setName(hno);
											streetDAO.writeBuilding(streetId, building);
										}
									}
								}
							}
						} else {
							String hno = i.getTag(OSMTagKey.ADDRESS_HOUSE);
							if (hno == null) {
								hno = i.getTag(OSMTagKey.ADDR_HOUSE_NUMBER);
							}
							if (hno == null) {
								hno = i.getTag(OSMTagKey.NAME);
							}
							members = i.getMembers("border"); //$NON-NLS-1$
							if (!members.isEmpty()) {
								Entity border = members.iterator().next();
								if (border != null) {
									EntityId id = EntityId.valueOf(border);
									// special check that address do not contain twice in a3 - border and separate a6
									if (!a6.getMemberIds().contains(id)) {
										Building building = new Building(border);
										if (building.getLocation() != null) {
											building.setName(hno);
											streetDAO.writeBuilding(streetId, building);
										} else {
											log.error("Strange border " + id + " location couldn't be found");
										}
									}
								}
							} else {
								log.info("For relation " + i.getId() + " border not found"); //$NON-NLS-1$ //$NON-NLS-2$
							}

						}
					}
				}
			}
		}
	}
	

	public String normalizeStreetName(String name) {
		name = name.trim();
		if (normalizeStreets) {
			String newName = name;
			boolean processed = newName.length() != name.length();
			for (String ch : normalizeDefaultSuffixes) {
				int ind = checkSuffix(newName, ch);
				if (ind != -1) {
					newName = cutSuffix(newName, ind, ch.length());
					processed = true;
					break;
				}
			}

			if (!processed) {
				for (String ch : normalizeSuffixes) {
					int ind = checkSuffix(newName, ch);
					if (ind != -1) {
						newName = putSuffixToEnd(newName, ind, ch.length());
						processed = true;
						break;
					}
				}
			}
			if (processed) {
				return newName;
			}
		}
		return name;
	}

	private int checkSuffix(String name, String suffix) {
		int i = -1;
		boolean searchAgain = false;
		do {
			i = name.indexOf(suffix, i);
			searchAgain = false;
			if (i > 0) {
				if (Character.isLetterOrDigit(name.charAt(i - 1))) {
					i++;
					searchAgain = true;
				}
			}
		} while (searchAgain);
		return i;
	}

	private String cutSuffix(String name, int ind, int suffixLength) {
		String newName = name.substring(0, ind);
		if (name.length() > ind + suffixLength + 1) {
			newName += name.substring(ind + suffixLength + 1);
		}
		return newName.trim();
	}

	private String putSuffixToEnd(String name, int ind, int suffixLength) {
		if (name.length() <= ind + suffixLength) {
			return name;

		}
		String newName;
		if (ind > 0) {
			newName = name.substring(0, ind);
			newName += name.substring(ind + suffixLength);
			newName += name.substring(ind - 1, ind + suffixLength);
		} else {
			newName = name.substring(suffixLength + 1) + name.charAt(suffixLength) + name.substring(0, suffixLength);
		}

		return newName.trim();
	}

	public Set<Long> getStreetInCity(Set<String> isInNames, String name, String nameEn, LatLon location) throws SQLException {
		if (name == null || location == null) {
			return Collections.emptySet();
		
		}
		name = normalizeStreetName(name);
		Set<City> result = new HashSet<City>();
		List<City> nearestObjects = new ArrayList<City>();
		nearestObjects.addAll(cityManager.getClosestObjects(location.getLatitude(),location.getLongitude()));
		nearestObjects.addAll(cityVillageManager.getClosestObjects(location.getLatitude(),location.getLongitude()));
		//either we found a city boundary the street is in
		for (City c : nearestObjects) {
			Boundary boundary = cityBoundaries.get(c);
			if (isInNames.contains(c.getName()) || (boundary != null && boundary.containsPoint(location))) {
				result.add(c);
			}
		}
		//or we need to find closes city
		if (result.isEmpty()) {
			City city = getClosestCity(location, isInNames, nearestObjects);
			if (city != null) {
				result.add(city);
			}
		}
		return registerStreetInCities(name, nameEn, location, result);
	}


	private Set<Long> registerStreetInCities(String name, String nameEn, LatLon location, Collection<City> result) throws SQLException {
		if (result.isEmpty()) {
			return Collections.emptySet();
		}
		if (Algoritms.isEmpty(nameEn)) {
			nameEn = Junidecode.unidecode(name);
		}

		Set<Long> values = new TreeSet<Long>();
		for (City city : result) {
			String cityPart = null;
			SimpleStreet foundStreet = streetDAO.findStreet(name, city);
			if (foundStreet != null) {
				//matching the nodes is done somewhere else. This is just a simple check if the streets are really close to each other
				if (MapUtils.getDistance(location, foundStreet.getLocation()) > 500) { 
					//oops, same street name within one city!
					if (foundStreet.getCityPart() == null) {
						//we need to update the city part first 
						String aCityPart = findCityPart(foundStreet.getLocation(), city);
						foundStreet = streetDAO.updateStreetCityPart(foundStreet, city, aCityPart != null ? aCityPart : city.getName());
					}
					//now, try to find our cityPart again
					cityPart = findCityPart(location, city);
					foundStreet = streetDAO.findStreet(name, city, cityPart);
				}
			}
			if (foundStreet == null) {
				//by default write city with cityPart of the city
				long streetId = streetDAO.insertStreet(name, nameEn, location, city, cityPart);
				values.add(streetId);
			} else {
				values.add(foundStreet.getId());
			}
		}
		return values;
	}

	private String findCityPart(LatLon location, City city) {
		String cityPart = city.getName();
		boolean found = false;
		Boundary cityBoundary = cityBoundaries.get(city);
		if (cityBoundary != null) {
			for(City subpart : boundariesToCities.get(cityBoundary)){
				if(subpart != city){
					Boundary subBoundary = cityBoundaries.get(subpart);
					if(cityBoundary != null && subBoundary != null &&  subBoundary.getAdminLevel() > cityBoundary.getAdminLevel()){
						cityPart = findNearestCityOrSuburb(subBoundary, location); //subpart.getName();
						found = true;
						break;
					}
				}
			}
		}
		if (!found) {
			Boundary b = cityBoundaries.get(city);
			cityPart = findNearestCityOrSuburb(b, location);
		}
		return cityPart;
	}

	private String findNearestCityOrSuburb(Boundary greatestBoundary,
			LatLon location) {
		String result = null;
		double dist = Double.MAX_VALUE;
		List<City> list = new ArrayList<City>();
		if(greatestBoundary != null) {
			result = greatestBoundary.getName();
			list = boundariesToCities.get(greatestBoundary);
		} else {
			list.addAll(cityManager.getClosestObjects(location.getLatitude(),location.getLongitude()));
			list.addAll(cityVillageManager.getClosestObjects(location.getLatitude(),location.getLongitude()));
		}
		for (City c : list) {
			double actualDistance = MapUtils.getDistance(location, c.getLocation());
			if (actualDistance < dist) {
				result = c.getName();
				dist = actualDistance;
			}
		}
		return result;
	}


	public City getClosestCity(LatLon point, Set<String> isInNames, Collection<City> nearCitiesAndVillages) {
		if (point == null) {
			return null;
		}
		// search by distance considering is_in names 
		City closest = null;
		double relDist = Double.POSITIVE_INFINITY;
		for (City c : nearCitiesAndVillages) {
			if(isInNames.contains(c.getName())){
				return c;
			}
			double rel = MapUtils.getDistance(c.getLocation(), point) / c.getType().getRadius();
			if (rel < relDist) {
				closest = c;
				relDist = rel;
				if (relDist < 0.2d && isInNames.isEmpty()) {
					return closest;
				}
			}
		}
		return closest;
	}
	
	public void iterateMainEntity(Entity e, OsmDbAccessorContext ctx) throws SQLException {
		// index not only buildings but also nodes that belongs to addr:interpolation ways
		if (e.getTag(OSMTagKey.ADDR_HOUSE_NUMBER) != null && e.getTag(OSMTagKey.ADDR_STREET) != null) {
			// TODO e.getTag(OSMTagKey.ADDR_CITY) could be used to find city however many cities could have same name!
			// check that building is not registered already
			boolean exist = streetDAO.findBuilding(e);
			if (!exist) {
				ctx.loadEntityData(e);
				LatLon l = e.getLatLon();
				Set<Long> idsOfStreet = getStreetInCity(e.getIsInNames(), e.getTag(OSMTagKey.ADDR_STREET), null, l);
				if (!idsOfStreet.isEmpty()) {
					Building building = new Building(e);
					building.setName(e.getTag(OSMTagKey.ADDR_HOUSE_NUMBER));
					streetDAO.writeBuilding(idsOfStreet, building);
				}
			}
		} else if (e instanceof Way /* && OSMSettings.wayForCar(e.getTag(OSMTagKey.HIGHWAY)) */
				&& e.getTag(OSMTagKey.HIGHWAY) != null && e.getTag(OSMTagKey.NAME) != null) {
			// suppose that streets with names are ways for car
			// Ignore all ways that have house numbers and highway type
			boolean exist = false;

			// if we saved address ways we could checked that we registered before
			if (saveAddressWays) {
				exist = streetDAO.findStreetNode(e);
			}

			// check that street way is not registered already
			if (!exist) {
				ctx.loadEntityData(e);
				LatLon l = e.getLatLon();
				Set<Long> idsOfStreet = getStreetInCity(e.getIsInNames(), e.getTag(OSMTagKey.NAME), e.getTag(OSMTagKey.NAME_EN), l);
				if (!idsOfStreet.isEmpty() && saveAddressWays) {
					streetDAO.writeStreetWayNodes(idsOfStreet, (Way) e);
				}
			}
		}
		if (e instanceof Relation) {
			if (e.getTag(OSMTagKey.POSTAL_CODE) != null) {
				ctx.loadEntityData(e);
				postalCodeRelations.add((Relation) e);
			}
		}
	}
	
	private void writeCity(City city) throws SQLException {
		addressCityStat.setLong(1, city.getId());
		addressCityStat.setDouble(2, city.getLocation().getLatitude());
		addressCityStat.setDouble(3, city.getLocation().getLongitude());
		addressCityStat.setString(4, city.getName());
		addressCityStat.setString(5, city.getEnName());
		addressCityStat.setString(6, CityType.valueToString(city.getType()));
		addBatch(addressCityStat);
	}
	
	
	private boolean debugFullNames = false; //true to see atached cityPart and boundaries to the street names
	
	public void writeCitiesIntoDb() throws SQLException {
		for (City c : cities.values()) {
			writeCity(c);
		}
		// commit to put all cities
		if (pStatements.get(addressCityStat) > 0) {
			addressCityStat.executeBatch();
			pStatements.put(addressCityStat, 0);
			mapConnection.commit();
		}
	}
	
	public void processingPostcodes() throws SQLException {
		streetDAO.commit();
		PreparedStatement pstat = mapConnection.prepareStatement("UPDATE building SET postcode = ? WHERE id = ?");
		pStatements.put(pstat, 0);
		for (Relation r : postalCodeRelations) {
			String tag = r.getTag(OSMTagKey.POSTAL_CODE);
			for (EntityId l : r.getMemberIds()) {
				pstat.setString(1, tag);
				pstat.setLong(2, l.getId());
				addBatch(pstat);
			}
		}
		if (pStatements.get(pstat) > 0) {
			pstat.executeBatch();
		}
		pStatements.remove(pstat);
	}


	public void writeBinaryAddressIndex(BinaryMapIndexWriter writer, String regionName, IProgress progress) throws IOException, SQLException {
		streetDAO.close();
		closePreparedStatements(addressCityStat);
		mapConnection.commit();
		boolean readWayNodes = saveAddressWays;

		writer.startWriteAddressIndex(regionName);
		List<City> cities = readCities(mapConnection);
		Collections.sort(cities, new Comparator<City>() {

			@Override
			public int compare(City o1, City o2) {
				if (o1.getType() != o2.getType()) {
					return (o1.getType().ordinal() - o2.getType().ordinal());
				}
				return Collator.getInstance().compare(o1.getName(), o2.getName());
			}
		});
		PreparedStatement streetstat = mapConnection.prepareStatement(//
				"SELECT A.id, A.name, A.name_en, A.latitude, A.longitude, "+ //$NON-NLS-1$
				"B.id, B.name, B.name_en, B.latitude, B.longitude, B.postcode, A.cityPart "+ //$NON-NLS-1$
				"FROM street A left JOIN building B ON B.street = A.id JOIN city C ON A.city = C.id " + //$NON-NLS-1$
				//with this order by we get the streets directly in city to not have the suffix if duplication
				//TODO this order by might slow the query a little bit
				"WHERE A.city = ? ORDER BY C.name == A.cityPart DESC"); //$NON-NLS-1$
		PreparedStatement waynodesStat = null;
		if (readWayNodes) {
			waynodesStat = mapConnection.prepareStatement("SELECT A.id, A.latitude, A.longitude FROM street_node A WHERE A.street = ? "); //$NON-NLS-1$
		}

		int j = 0;
		int csize = cities.size(); 
		for (; j < csize; j++) {
			City c = cities.get(j);
			if (c.getType() != CityType.CITY && c.getType() != CityType.TOWN) {
				break;
			}
		}
		progress.startTask(Messages.getString("IndexCreator.SERIALIZING_ADRESS"), j + ((csize - j) / 100 + 1)); //$NON-NLS-1$

		Map<String, Set<Street>> postcodes = new TreeMap<String, Set<Street>>();
		boolean writeCities = true;
		
		// collect suburbs with is in value
		List<City> suburbs = new ArrayList<City>();
		for(City s : cities){
			if(s.getType() == CityType.SUBURB && s.getIsInValue() != null){
				suburbs.add(s);
			}
		}

		// write cities and after villages
		writer.startCityIndexes(false);
		for (int i = 0; i < csize; i++) {
			City c = cities.get(i);
			List<City> listSuburbs = null;
			for (City suburb : suburbs) {
				if (suburb.getIsInValue().contains(c.getName().toLowerCase())) {
					if(listSuburbs == null){
						listSuburbs = new ArrayList<City>();
					}
					listSuburbs.add(suburb);
				}
			}
			if (writeCities) {
				progress.progress(1);
			} else if ((csize - i) % 100 == 0) {
				progress.progress(1);
			}
			if (writeCities && c.getType() != CityType.CITY && c.getType() != CityType.TOWN) {
				writer.endCityIndexes(false);
				writer.startCityIndexes(true);
				writeCities = false;
			}

			Map<Street, List<Node>> streetNodes = null;
			if (readWayNodes) {
				streetNodes = new LinkedHashMap<Street, List<Node>>();
			}
			long time = System.currentTimeMillis();
			List<Street> streets = readStreetsBuildings(streetstat, c, waynodesStat, streetNodes, listSuburbs);
			long f = System.currentTimeMillis() - time;
			writer.writeCityIndex(c, streets, streetNodes);
			int bCount = 0;
			for (Street s : streets) {
				bCount++;
				for (Building b : s.getBuildings()) {
					bCount++;
					if (b.getPostcode() != null) {
						if (!postcodes.containsKey(b.getPostcode())) {
							postcodes.put(b.getPostcode(), new LinkedHashSet<Street>(3));
						}
						postcodes.get(b.getPostcode()).add(s);
					}
				}
			}
			if (f > 500) {
				if(logMapDataWarn != null) {
					logMapDataWarn.info("! " + c.getName() + " ! " + f + " " + bCount + " streets " + streets.size());
				} else {
					log.info("! " + c.getName() + " ! " + f + " " + bCount + " streets " + streets.size());
				}
			}
		}
		writer.endCityIndexes(!writeCities);

		// write postcodes
		writer.startPostcodes();
		for (String s : postcodes.keySet()) {
			writer.writePostcode(s, postcodes.get(s));
		}
		writer.endPostcodes();

		progress.finishTask();

		writer.endWriteAddressIndex();
		writer.flush();
		streetstat.close();
		if (readWayNodes) {
			waynodesStat.close();
		}

	}

	public void commitToPutAllCities() throws SQLException {
		// commit to put all cities
		streetDAO.commit();
	}

	public void createDatabaseStructure(Connection mapConnection, DBDialect dialect) throws SQLException {
		this.mapConnection = mapConnection;
		streetDAO.createDatabaseStructure(mapConnection, dialect);
		createAddressIndexStructure(mapConnection, dialect);
		addressCityStat = mapConnection.prepareStatement("insert into city (id, latitude, longitude, name, name_en, city_type) values (?, ?, ?, ?, ?, ?)");

		pStatements.put(addressCityStat, 0);
	}
	
	private void createAddressIndexStructure(Connection conn, DBDialect dialect) throws SQLException{
		Statement stat = conn.createStatement();
		
        stat.executeUpdate("create table city (id bigint primary key, latitude double, longitude double, " +
        			"name varchar(1024), name_en varchar(1024), city_type varchar(32))");
        stat.executeUpdate("create index city_ind on city (id, city_type)");
        
//        if(dialect == DBDialect.SQLITE){
//        	stat.execute("PRAGMA user_version = " + IndexConstants.ADDRESS_TABLE_VERSION); //$NON-NLS-1$
//        }
        stat.close();
	}
	
	private List<Street> readStreetsBuildings(PreparedStatement streetBuildingsStat, City city,
			PreparedStatement waynodesStat, Map<Street, List<Node>> streetNodes, List<City> citySuburbs) throws SQLException {
		TLongObjectHashMap<Street> visitedStreets = new TLongObjectHashMap<Street>();
		Map<String,List<StreetAndDistrict>> uniqueNames = new HashMap<String,List<StreetAndDistrict>>();
		Map<String,Street> streets = new HashMap<String,Street>();
		
		//read streets for city
		readStreatsByBuildingsForCity(streetBuildingsStat, city, streets,
				waynodesStat, streetNodes, visitedStreets, uniqueNames);
		//read streets for suburbs of the city
		if (citySuburbs != null) {
			for (City suburb : citySuburbs) {
				readStreatsByBuildingsForCity(streetBuildingsStat, suburb, streets, waynodesStat, streetNodes, visitedStreets, uniqueNames);
			}
		}
		return new ArrayList<Street>(streets.values());
	}


	private void readStreatsByBuildingsForCity(
			PreparedStatement streetBuildingsStat, City city,
			Map<String,Street> streets, PreparedStatement waynodesStat,
			Map<Street, List<Node>> streetNodes,
			TLongObjectHashMap<Street> visitedStreets, Map<String,List<StreetAndDistrict>> uniqueNames) throws SQLException {
		streetBuildingsStat.setLong(1, city.getId());
		ResultSet set = streetBuildingsStat.executeQuery();
		while (set.next()) {
			long streetId = set.getLong(1);
			if (!visitedStreets.containsKey(streetId)) {
				Street street = new Street(null);
				String streetName = set.getString(2);
				street.setLocation(set.getDouble(4), set.getDouble(5));
				street.setId(streetId);
				//load the street nodes
				loadStreetNodes(street, waynodesStat, streetNodes);
				
				//If there are more streets with same name in different districts. 
				//Add district name to all other names. If sorting is right, the first street was the one in the city
				String defaultDistrict = set.getString(12);
				String district = identifyBestDistrict(street, streetName, " (" + defaultDistrict + ")", uniqueNames, streetNodes);
				street.setName(streetName + district);
				street.setEnName(set.getString(3) + district);
				//if for this street there is already same street, add just nodes to the street.
				if (!streets.containsKey(street.getName())) {
					streets.put(street.getName(),street);
				} else {
					//add the current streetNodes to the existing street
					List<Node> firstStreetNodes = streetNodes.get(streets.get(street.getName()));
					if (firstStreetNodes != null && streetNodes.get(street) != null) {
						firstStreetNodes.addAll(streetNodes.get(street));
					}
				}
				visitedStreets.put(streetId, street); //mark the street as visited
			}
			if (set.getObject(6) != null) {
				Street s = visitedStreets.get(streetId);
				Building b = new Building();
				b.setId(set.getLong(6));
				b.setName(set.getString(7));
				b.setEnName(set.getString(8));
				b.setLocation(set.getDouble(9), set.getDouble(10));
				b.setPostcode(set.getString(11));
				s.registerBuilding(b);
			}
		}

		set.close();
	}


	private void loadStreetNodes(Street street, PreparedStatement waynodesStat, Map<Street, List<Node>> streetNodes)
			throws SQLException {
		if (waynodesStat != null && streetNodes != null) {
			List<Node> list = streetNodes.get(street); 
			if (list == null) {
				list = new ArrayList<Node>();
				streetNodes.put(street, list);
			}
			waynodesStat.setLong(1, street.getId());
			ResultSet rs = waynodesStat.executeQuery();
			while (rs.next()) {
				list.add(new Node(rs.getDouble(2), rs.getDouble(3), rs.getLong(1)));
			}
			rs.close();
		}
	}


	private String identifyBestDistrict(final Street street, final String streetName, final String district,
			final Map<String, List<StreetAndDistrict>> uniqueNames, Map<Street, List<Node>> streetNodes) {
		String result = debugFullNames ?  district : ""; //TODO make it an option
		List<StreetAndDistrict> sameStreets = uniqueNames.get(streetName);
		if (sameStreets == null) {
			sameStreets = new ArrayList<StreetAndDistrict>(1);
			uniqueNames.put(streetName, sameStreets);
		} else {
			result = district;
			// not unique, try to find best matching street with district
			// if not found, use the one that is assign to this street
			similarStreets:	for (StreetAndDistrict ld : sameStreets) {
				//try to find the closes nodes to each other!
				if (streetNodes != null) {
					for (Node n1 : streetNodes.get(street)) {
						for (Node n2 : streetNodes.get(ld.getStreet())) {
							if (MapUtils.getDistance(n1.getLatLon(), n2.getLatLon()) < 400) {
								result = ld.getDistrict();
								break similarStreets;
							}
						}
					}
				}
				if (MapUtils.getDistance(ld.getStreet().getLocation(), street.getLocation()) < 400) {
					result = ld.getDistrict();
					break;
				}
			}
		}
		sameStreets.add(new StreetAndDistrict(street, result));
		return result;
	}
	

	public List<City> readCities(Connection c) throws SQLException{
		List<City> cities = new ArrayList<City>();
		Statement stat = c.createStatement();
		ResultSet set = stat.executeQuery("select id, latitude, longitude , name , name_en , city_type from city"); //$NON-NLS-1$
		while(set.next()){
			City city = new City(CityType.valueFromString(set.getString(6)));
			city.setName(set.getString(4));
			city.setEnName(set.getString(5));
			city.setLocation(set.getDouble(2), 
					set.getDouble(3));
			city.setId(set.getLong(1));
			cities.add(city);
			
			if (debugFullNames) { //TODO make it an option...
				Boundary cityB = cityBoundaries.get(city);
				if (cityB != null) {
					city.setName(city.getName() + " " + cityB.getAdminLevel() + ":" + cityB.getName());
				}
			}
		}
		set.close();
		stat.close();
		return cities;
	}
}
