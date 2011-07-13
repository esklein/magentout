package com.woe.adapter;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.code.magja.model.category.Category;
import com.google.code.magja.model.media.Media;
import com.google.code.magja.model.product.Product;
import com.google.code.magja.model.product.ProductAttribute;
import com.google.code.magja.model.product.ProductMedia;
import com.google.code.magja.model.product.ProductType;
import com.google.code.magja.model.product.ProductTypeEnum;
import com.google.code.magja.model.product.ProductVisibilityEnum;
import com.google.code.magja.service.RemoteServiceFactory;
import com.google.code.magja.service.ServiceException;
import com.google.code.magja.service.category.CategoryRemoteService;
import com.google.code.magja.service.product.ProductAttributeRemoteService;
import com.google.code.magja.service.product.ProductLinkRemoteService;
import com.google.code.magja.service.product.ProductRemoteService;
import com.woe.sql.ConnectionManager;

/**
 * This is the main class which is fired from the CheckoutListener class when
 * Notifications are heard. This class will query the Checkout POS system and
 * develop a Magento Model. Finally, this model will be sent to Magento API.
 * 
 * @author esklein
 * 
 */
/**
 * @author esklein
 * 
 */
public class InventoryAdapter {

	private Connection conn;
	private ProductRemoteService productService;
	private CategoryRemoteService categoryService;
	private ProductAttributeRemoteService attributeService;
	private ProductLinkRemoteService productLinkService;
	private List<Object> configurableProductsAdded;

	static Logger logger = Logger.getLogger("com.woe.adapter.InventoryAdapter");

	/**
	 * Everything starts here.
	 */
	public static void main(String args[]) throws Exception {
		logger.setLevel(Level.INFO);
		InventoryAdapter iva = new InventoryAdapter();
		iva.start();
	}

	/**
	 * Constructor - Instantiate needed services
	 */
	public InventoryAdapter() {
		try {
			conn = ConnectionManager.getConnection();
			productService = new RemoteServiceFactory()
					.getProductRemoteService();
			productLinkService = new RemoteServiceFactory()
					.getProductLinkRemoteService();
			categoryService = new RemoteServiceFactory()
					.getCategoryRemoteService();
			attributeService = new RemoteServiceFactory()
					.getProductAttributeRemoteService();
			configurableProductsAdded = new ArrayList<Object>();

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * Begin. The sequence of operations first adds New Products, Updates, and
	 * Deletes.
	 * 
	 */
	@SuppressWarnings("deprecation")
	public void start() {
		logger.info("*** INVENTORY ADAPTER PROCCESS - STARTING AT: "
				+ new Date().toLocaleString().toUpperCase());
		final long startTime = System.currentTimeMillis();
		final long endTime;
		try {
			// A notification has hit, call these three methods to sync with
			// Magento.
			commitNewProducts();
			commitUpdatedProducts();
			commitDeletedProducts();
		} finally {
			endTime = System.currentTimeMillis();
		}
		final long duration = ((endTime - startTime) / 1000);
		logger.info("*** INVENTORY ADAPTER PROCCESS - COMPLETED IN: "
				+ duration + " SECONDS");
		logger.info("---------------------------------------------------------------------");
	}

	/**
	 * Find NEW Products which have been added to checkout.
	 */
	private void commitNewProducts() {
		try {
			int countProductsAdded = 0;
			String stmt = "SELECT * " + "FROM ITEM "
					+ "WHERE SYNC_STATUS = ? AND SORTKEY_STRING1 IS NOT NULL";

			PreparedStatement p = conn.prepareStatement(stmt);
			p.setString(1, "OUT_OF_SYNC_INSERT");
			ResultSet rs = p.executeQuery();

			while (rs.next()) {
				Product product = null;
				int posProductId = rs.getInt("id");
				int posParentId = rs.getInt("id_parent");

				logger.info("--- ADDING NEW PRODUCT TO WEBSITE: "
						+ posProductId);
				try {
					/* Create the product model from checkout POS */
					product = getProductModel(posProductId,
							InventoryConstants.NEW_ACTION);

					/*
					 * If this is a child product we don't need it to be visible
					 * in the shopping cart
					 */
					if (posParentId > 0) {
						product
								.setVisibility(ProductVisibilityEnum.VISIBILITY_NOT_VISIBLE);
					}
					productService.save(product);

					/*
					 * If this is a parent product see if it needs to be set as
					 * a configurable product withing magento.
					 * 
					 * setConfigurableProduct() should determine if it needes to
					 * be set as a Configurable product.
					 * 
					 * setParentLink() will assign a simple product to it's
					 * parent 'configurable product in Magento'
					 * 
					 * setProductAttribute() will then set attributes on product
					 * i.e. (Color, Size, Etc).
					 */
					if (posParentId > 0) {
						setConfigurableProduct(posParentId);
						setParentLink(product, posParentId);
						setProductAttribute(posProductId, product);
					}

				} catch (ServiceException e) {
					e.printStackTrace();
					countProductsAdded--;
				} catch (NullPointerException e) {
					/* This product is malformed in Checkout. */
					logger.info("--- UNABLE TO UPLOAD PRODUCT: "
							+ posProductId);
					countProductsAdded--;
					e.printStackTrace();
				}
				countProductsAdded++;

				// Update the checkout database and mark the product as SYNCED.
				this.updateSyncStatus(rs.getInt("id"), product.getId());
			}

			logger.info("*** ADDING NEW PRODUCTS COMPLETE - TOTAL ADDED SUCCESSFULY: "
							+ countProductsAdded);

		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Find UPDATED products in Checkout POS.
	 */
	private void commitUpdatedProducts() {
		try {
			int countProductsUpdated = 0;

			String stmt = "SELECT * " + "FROM ITEM " + "WHERE SYNC_STATUS = ?";

			PreparedStatement p = conn.prepareStatement(stmt);
			p.setString(1, "OUT_OF_SYNC_UPDATE");
			ResultSet rs = p.executeQuery();

			while (rs.next()) {
				Product product = null;
				int posProductId = rs.getInt("id");
				logger.info("--- UPDATING PRODUCT ON WEBSITE: "
						+ rs.getString("sortkey_string1"));
				try {
					// Save the product and updated changed properties (price,
					// qty, etc)
					product = getProductModel(posProductId,
							InventoryConstants.UPDATE_ACTION);
					productService.save(product);
				} catch (ServiceException e) {
					e.printStackTrace();
					countProductsUpdated--;
				}
				// Mark product as synced
				this.updateSyncStatus(posProductId, product.getId());
				countProductsUpdated++;
			}

			logger.info("*** UPDATING PRODUCTS COMPLETE - TOTAL UPDATED SUCCESSFULY: "
							+ countProductsUpdated);
		} catch (SQLException e) {
			e.printStackTrace();
		}

	}

	/**
	 * Find DELETED products in checkout. We're not doing anything with this
	 * yet.
	 */
	private void commitDeletedProducts() {
		try {
			String stmt = "SELECT * " + "FROM ITEM " + "WHERE SYNC_STATUS = ?";

			PreparedStatement p = conn.prepareStatement(stmt);
			p.setString(1, "OUT_OF_SYNC_DELETE");
			ResultSet rs = p.executeQuery();

			while (rs.next()) {
				// this.updateSyncStatus(rs.getInt("id"), null);
				logger.info("*** DELETING PRODUCT FROM WEBSITE: "
						+ rs.getString("sortkey_string1"));
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Set the product in Checkout POS as IN_SYNC
	 * 
	 * @param id
	 */
	private void updateSyncStatus(int posId, int magentoId) {
		try {
			String stmt = "UPDATE ITEM SET SYNC_STATUS = ? , SYNC_ID = ? WHERE id = ?";
			PreparedStatement p = conn.prepareStatement(stmt);
			p.setString(1, "IN_SYNC");
			p.setInt(2, magentoId);
			p.setInt(3, posId);
			p.execute();
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Method to develop product model from POS database.
	 * 
	 * @param id
	 * @param action
	 * @return
	 */
	private Product getProductModel(int id, String action) {
		Product product = null;
		try {
			product = new Product();

			/*
			 * Main query to get products properties from checkouts database -
			 * Checkout uses an EAV model, each property will come from the DB
			 * as a row.
			 * 
			 * We use InventoryConstants to mange which properties are assigned
			 * to int's.
			 */
			String stmt = "SELECT id_metatype, value " + "FROM metavalue "
					+ "WHERE id IN (" + "SELECT max(id) " + "FROM metavalue "
					+ "WHERE id_item= ? " + "GROUP BY id_metatype) UNION ALL "
					+ "SELECT id_metatype, CAST(value as text) "
					+ "FROM metanumber " + "WHERE id IN (" + "SELECT max(id) "
					+ "FROM metanumber " + "WHERE id_item= ? "
					+ "GROUP BY id_metatype);";

			PreparedStatement p = conn.prepareStatement(stmt);
			p.setInt(1, id);
			p.setInt(2, id);

			ResultSet rs = p.executeQuery();

			// Set properties on new product DTO
			while (rs.next()) {

				switch (rs.getInt("id_metatype")) {
					case InventoryConstants.PROPERTY_NAME :
						product.setName(rs.getString("value"));
						// logger.info("Setting Name");
						break;
					case InventoryConstants.PROPERTY_BARCODE :
						// logger.info("Setting Barcode");
						break;
					case InventoryConstants.PROPERTY_BRAND :
						product.set("brand", rs.getString("value"));
						break;
					case InventoryConstants.PROPERTY_CODE :
						product.setSku(rs.getString("value"));
						// logger.info("Setting Code" +
						// product.getSku());
						break;
					case InventoryConstants.PROPERTY_COST :
						product.setCost(rs.getDouble("value"));
						// logger.info("Setting Cost");
						break;
					case InventoryConstants.PROPERTY_PRICE :
						product.setPrice(rs.getDouble("value"));
						// logger.info("Setting Price");
						break;
					case InventoryConstants.PROPERTY_WEIGHT :
						product.setWeight(rs.getDouble("value") * 2.2);
						// logger.info("Setting Weight");
						break;

					// Deprecated - TAGS
					/*
					 * case InventoryConstants.PROPERTY_TAGS : List<Category>
					 * categoriesList = this
					 * .convertTagsToCategories(rs.getString("value")); if
					 * (categoriesList != null)
					 * product.setCategories(categoriesList); break;
					 */

					// Deprecated - DESCRIPTION
					/*
					 * case InventoryConstants.PROPERTY_DESCRIPTON :
					 * product.setDescription(rs.getString("value")); //
					 * logger.info("Setting Description"); break;
					 */
				}
			}

			/*
			 * Set stock and determine if product should be marked as in stock
			 * or not
			 */
			product.setQty(this.getProductQuantity(id));

			// Boiler plate code to make it a 'valid' product.
			product.setEnabled(true);
			Integer[] websites = {1};
			product.setWebsites(websites);

		} catch (SQLException e) {
			e.printStackTrace();
		}
		return product;
	}

	/**
	 * Returns the current stock level for a given product in Checkout.
	 * 
	 * @param id
	 * @return
	 */
	private Double getProductQuantity(int id) {
		/* Get stock in seperate query */
		Double qty = null;
		String stmt = "SELECT quantity from stock where id_product = ? AND stocktype = 1";
		try {
			PreparedStatement ps = conn.prepareStatement(stmt);
			ps.setInt(1, id);
			ResultSet rs = ps.executeQuery();
			while (rs.next()) {
				qty = rs.getDouble("quantity");
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
		return qty;
	}

	/**
	 * This will get images from Checkout POS and push them to Magentoo. There
	 * are currently issues with being able to pull from Postgre.
	 * 
	 * Will come back to this later.
	 * 
	 * @param id
	 * @return
	 */
	private List<ProductMedia> getProductImages(int id) {
		List<ProductMedia> images = null;
		String stmt = "SELECT content from asset where id_item = ?";
		try {
			if (images == null)
				images = new ArrayList<ProductMedia>();
			PreparedStatement ps = conn.prepareStatement(stmt);
			ps.setInt(1, id);
			ResultSet rs = ps.executeQuery();

			while (rs.next()) {
				logger.info("Adding image to product length:"
						+ rs.getBytes("content").length);
				Set<ProductMedia.Type> types = new HashSet<ProductMedia.Type>();
				types.add(ProductMedia.Type.IMAGE);

				InputStream raw = rs.getBinaryStream("content");
				// InputStream in = new BufferedInputStream(raw);

				int contentLength = rs.getBytes("content").length;
				byte[] data = new byte[contentLength];
				int bytesRead = 0;
				int offset = 0;
				while (offset < contentLength) {
					bytesRead = raw.read(data, offset, data.length - offset);
					logger.info("Only read " + offset
							+ " bytes; Expected " + contentLength + " bytes");
					if (bytesRead == -1)
						break;
					offset += bytesRead;
				}
				raw.close();

				Media image = new Media();
				image.setData(data);
				image.setName("test" + UUID.randomUUID());
				image.setMime("image/jpeg");

				ProductMedia media = new ProductMedia();
				media.setImage(image);
				media.setExclude(false);
				media.setImage(image);
				media.setLabel("Testing that");
				media.setPosition(1);
				media.setTypes(types);

				images.add(media);

			}
		} catch (SQLException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		return images;
	}

	/**
	 * Takes tags string and converts them to categories for Magento.
	 * 
	 * @param tags
	 * @return
	 */
	private List<Category> convertTagsToCategories(String tags) {
		List<Category> categoriesList = null;

		if (tags != null && tags.length() > 0) {
			String[] tagsArray = tags.split(",");
			for (int i = 0; i < tagsArray.length; i++) {
				{
					if (categoriesList == null)
						categoriesList = new ArrayList<Category>();
					try {
						if ((tagsArray[i]).length() > 0 && tagsArray[i] != "") {
							/*
							 * Create new category object from magento and add
							 * it to list + product
							 */
							Category category = new Category();
							category = categoryService
									.getByIdWithParent(Integer
											.parseInt(tagsArray[i].trim()));
							categoriesList.add(category);

							while (category.getParent() != null) {
								/*
								 * Cannot get default category (1) from Magento,
								 * so we need to have else statement to handle
								 * when we're done traversing to the root
								 * category
								 */
								int categoryId = category.getParent().getId();
								if (categoryId != InventoryConstants.DEFAULT_CATEGORY) {
									category = categoryService
											.getByIdWithParent(categoryId);
								} else {
									category = categoryService
											.getDefaultParent();
								}
								categoriesList.add(category);
							}

						}
					} catch (ServiceException e) {
						e.printStackTrace();
					} catch (NumberFormatException e) {
						/*
						 * Last value of tags are comma seperated, however the
						 * trailing comma at the end causes exception - swallow
						 * it. e.printStackTrace();
						 */
					}
				}

			}
		}
		if (!categoriesList.isEmpty()) {
			return categoriesList;
		} else {
			return null;
		}

	}

	/**
	 * Set a product as Configurable in Magento. This means it will wrap simple
	 * products with attributes such as Size, Color, etc
	 * 
	 * @param posProductId
	 */
	private void setConfigurableProduct(int posProductId) {
		try {

			/* Pull sku from product so we know what we're dealing with */
			Product parentProduct = null;
			String stmt = "SELECT sync_id from item where id = ?";
			PreparedStatement p = conn.prepareStatement(stmt);
			p.setInt(1, posProductId);

			ResultSet rs = p.executeQuery();

			while (rs.next()) {
				try {
					/*
					 * Check configurable product SKU ID - see if we can find it
					 * within Magento
					 */
					Integer id = rs.getInt("sync_id");
					if (configurableProductsAdded.contains(id))
						return;
					// logger.info("TRYING TO GET: " + sku);
					parentProduct = productService.getById(id);
				} catch (ServiceException e) {
					e.printStackTrace();
				}
			}

			if (parentProduct != null
					&& parentProduct.getType() != ProductTypeEnum.CONFIGURABLE
							.getProductType()
					&& !configurableProductsAdded.contains(parentProduct
							.getId())) {
				logger.info("^^^ PRODUCT IS A PARENT - SETTING TO CONFIGURABLE");

				productService.setProductTypeConfigurable(parentProduct);
				configurableProductsAdded.add(parentProduct.getId());
				/*
				 * We found a configurable product, set the super attributes of
				 * it
				 */
				setSuperAttribute(parentProduct, posProductId);
			}

		} catch (ServiceException e) {
			e.printStackTrace();
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Find what super attributes a product has and assign them to the
	 * Configurable product. If we don't have it in Magento, add it.
	 * 
	 * @param product
	 * @param posProductId
	 */
	private void setSuperAttribute(Product product, int posProductId) {
		// Select charachteristics from POS
		try {
			String stmt = "select distinct description from characteristic where id IN("
					+ "select id_characteristic  from permutation where id IN("
					+ "select id_permutation from item_permutation where id_item IN ("
					+ "select id from item where id_parent = ?)))";

			PreparedStatement p;
			p = conn.prepareStatement(stmt);
			p.setInt(1, posProductId);
			ResultSet rs = p.executeQuery();

			while (rs.next()) {
				String attributeCode = rs.getString("description");
				ProductAttribute attribute = null;

				try {
					attribute = attributeService.getByCode(attributeCode
							.toLowerCase());
				} catch (ServiceException e) {
					e.printStackTrace();
				}

				if (attribute == null) {
					attribute = createAttribute(attributeCode);
				}

				productService.setSuperAttribute(product, attribute);
			}

		} catch (SQLException e) {
			e.printStackTrace();
		} catch (ServiceException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Assign a child product to their respective parent in Magento.
	 * 
	 * @param childProduct
	 * @param posProductId
	 */
	private void setParentLink(Product childProduct, int posProductId) {
		try {
			Product parentProduct = null;
			String stmt = "SELECT sync_id from item where id = ?";
			PreparedStatement p = conn.prepareStatement(stmt);
			p.setInt(1, posProductId);

			ResultSet rs = p.executeQuery();

			while (rs.next()) {
				Integer id = rs.getInt("sync_id");
				parentProduct = productService.getById(id);
			}

			productLinkService.assignSimpleToConfigurable(childProduct,
					parentProduct);

		} catch (ServiceException e) {
			e.printStackTrace();
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Set a product attribute that corresponds to a super attribute. i.e. Blue,
	 * Small, Etc.
	 * 
	 * If the option doesn't exist within an attribute, add it.
	 * 
	 * @param posProductId
	 * @param product
	 */
	private void setProductAttribute(int posProductId, Product product) {
		try {
			ProductAttribute attribute = null;

			String stmt = "select c.description AS super_attribute, p.description AS attribute "
					+ "from permutation p, characteristic c where p.id in ("
					+ "select id_permutation from item_permutation where id_item = ?)"
					+ "and p.id_characteristic = c.id";
			PreparedStatement p = conn.prepareStatement(stmt);
			p.setInt(1, posProductId);
			ResultSet rs = p.executeQuery();

			while (rs.next()) {
				Boolean optionSet = false;
				String superAttribute = rs.getString("super_attribute");
				attribute = attributeService.getByCode(superAttribute
						.toLowerCase());
				attributeService.getOptions(attribute);
				String magentoAttributeValue = rs.getString("attribute")
						.toLowerCase();

				if (attribute.getOptions() != null) {
					for (Map.Entry<Integer, String> opt : attribute
							.getOptions().entrySet()) {
						// logger.info("STARTING WITH" + opt.toString());

						String posAttributeValue = opt.toString().substring(
								opt.toString().indexOf('=') + 1).toLowerCase();
						String magentoAttributeValueId = opt.toString()
								.substring(0, opt.toString().indexOf('='));

						// logger.info("COMPARING " + posAttributeValue
						// + " TO " + magentoAttributeValue + " ID "
						// + magentoAttributeValueId);

						if (posAttributeValue
								.equalsIgnoreCase(magentoAttributeValue)) {
							// logger.info("Option is avalable - Adding:"
							// + magentoAttributeValue);
							product.set(attribute.getCode(),
									magentoAttributeValueId);
							productService.save(product);
							optionSet = true;
							break;
						}
					}
				}
				if (!optionSet) {
					// logger.info("Option is NOT avalable - Adding:" +
					// magentoAttributeValue);
					attributeService.addOption(attribute, InventoryHelper
							.capitalize(magentoAttributeValue));
					// Recursive function - EEK, we'll see how this goes.
					this.setProductAttribute(posProductId, product);
				}
			}

		} catch (SQLException e) {
			e.printStackTrace();
		} catch (ServiceException e) {
			e.printStackTrace();
		}

	}

	/**
	 * Create a new attribute if it's non-existant.
	 * 
	 * @param attributeName
	 * @return
	 */
	private ProductAttribute createAttribute(String attributeName) {

		ProductAttribute attribute = new ProductAttribute();
		attribute.setCode(attributeName.toLowerCase());
		attribute.setScope(ProductAttribute.Scope.GLOBAL);
		attribute.setGroup("General");
		attribute.setType("varchar");
		attribute.setBackend("");
		attribute.setFrontend("");
		attribute.setLabel(InventoryHelper.capitalize(attributeName));
		attribute.setInput("select");
		attribute.setAttributeClass("");
		attribute.setSource("");
		attribute.setVisible(true);
		attribute.setRequired(false);
		attribute.setUserDefined(true);
		attribute.setDefaultValue("");
		attribute.setSearchable(true);
		attribute.setFilterable(true);
		attribute.setComparable(true);
		attribute.setVisibleOnFront(false);
		attribute.setVisibleInAdvancedSearch(true);
		attribute.setUnique(false);

		attribute.setApplyTo(new ArrayList<ProductType>());
		attribute.getApplyTo().add(ProductTypeEnum.SIMPLE.getProductType());

		try {
			attributeService.save(attribute);
		} catch (ServiceException e) {
			e.printStackTrace();
		}
		return attribute;
	}

}
