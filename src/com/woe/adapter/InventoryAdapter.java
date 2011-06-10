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

import com.google.code.magja.model.category.Category;
import com.google.code.magja.model.media.Media;
import com.google.code.magja.model.product.Product;
import com.google.code.magja.model.product.ProductAttribute;
import com.google.code.magja.model.product.ProductMedia;
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
public class InventoryAdapter {

    private Connection conn;
    private ProductRemoteService productService;
    private CategoryRemoteService categoryService;
    private ProductAttributeRemoteService attributeService;
    private ProductLinkRemoteService productLinkService;
    private List<Object> configurableProductsAdded;

    /**
     * Everything starts here.
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
     * And here..
     */
    @SuppressWarnings("deprecation")
    public void start() {
	System.out.println("*** INVENTORY ADAPTER PROCCESS - STARTING AT: "
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
	System.out.println("*** INVENTORY ADAPTER PROCCESS - COMPLETED IN: "
		+ duration + " SECONDS");
	System.out
		.println("---------------------------------------------------------------------");
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
		int posProductId = rs.getInt("id");
		int posParentId = rs.getInt("id_parent");

		System.out.println("--- ADDING NEW PRODUCT TO WEBSITE: "
			+ posProductId);
		try {
		    /* Create the product model from checkout POS */
		    Product product = getProductModel(posProductId,
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
		     * verifyConfigurableProduct() should determine if it needes
		     * to be set as a Configurable product After that we should
		     * assign a simple product to a configurable product, if
		     * need be.
		     */
		    if (posParentId > 0) {
			verifyConfigurableProduct(posParentId);
			setParentLink(product, posParentId);
			setProductAttribute(posProductId, product);
		    }

		} catch (ServiceException e) {
		    e.printStackTrace();
		    countProductsAdded--;
		} catch (NullPointerException e) {
		    /* This product is malformed in Checkout. */
		    System.out.println("--- UNABLE TO UPLOAD PRODUCT: "
			    + posProductId);
		    countProductsAdded--;
		    e.printStackTrace();
		}
		countProductsAdded++;
		this.updateSyncStatus(rs.getInt("id"));
	    }

	    System.out
		    .println("*** ADDING NEW PRODUCTS COMPLETE - TOTAL ADDED SUCCESSFULY: "
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
		int productId = rs.getInt("id");
		System.out.println("--- UPDATING PRODUCT ON WEBSITE: "
			+ rs.getString("sortkey_string1"));
		try {
		    productService.save(getProductModel(productId,
			    InventoryConstants.UPDATE_ACTION));
		} catch (ServiceException e) {
		    e.printStackTrace();
		    countProductsUpdated--;
		}
		this.updateSyncStatus(productId);
		countProductsUpdated++;
	    }

	    System.out
		    .println("*** UPDATING PRODUCTS COMPLETE - TOTAL UPDATED SUCCESSFULY: "
			    + countProductsUpdated);
	} catch (SQLException e) {
	    e.printStackTrace();
	}

    }

    /**
     * Find DELETED products in checkout
     */
    private void commitDeletedProducts() {
	try {
	    String stmt = "SELECT * " + "FROM ITEM " + "WHERE SYNC_STATUS = ?";

	    PreparedStatement p = conn.prepareStatement(stmt);
	    p.setString(1, "OUT_OF_SYNC_DELETE");
	    ResultSet rs = p.executeQuery();

	    while (rs.next()) {
		this.updateSyncStatus(rs.getInt("id"));
		System.out.println("DELETING PRODUCT FROM WEBSITE: "
			+ rs.getString("sortkey_string1"));
	    }
	} catch (SQLException e) {
	    e.printStackTrace();
	}
    }

    /**
     * @param id
     */
    private void updateSyncStatus(int id) {
	try {
	    String stmt = "UPDATE ITEM SET SYNC_STATUS = ? WHERE id = ?";
	    PreparedStatement p = conn.prepareStatement(stmt);
	    p.setString(1, "IN_SYNC");
	    p.setInt(2, id);
	    p.execute();
	} catch (SQLException e) {
	    e.printStackTrace();
	}
    }

    /**
     * Method to develop product model for shipment to magento.
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
	     * might be a much better way to do this.
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
		case InventoryConstants.PROPERTY_NAME:
		    product.setName(rs.getString("value"));
		    // System.out.println("Setting Name");
		    break;
		case InventoryConstants.PROPERTY_BARCODE:
		    // System.out.println("Setting Barcode");
		    break;
		case InventoryConstants.PROPERTY_BRAND:
		    product.set("brand", rs.getString("value"));
		    break;
		case InventoryConstants.PROPERTY_CODE:
		    product.setSku(rs.getString("value"));
		    // System.out.println("Setting Code" + product.getSku());
		    break;
		case InventoryConstants.PROPERTY_COST:
		    product.setCost(rs.getDouble("value"));
		    // System.out.println("Setting Cost");
		    break;
		case InventoryConstants.PROPERTY_DESCRIPTON:
		    product.setDescription(rs.getString("value"));
		    // System.out.println("Setting Description");
		    break;
		case InventoryConstants.PROPERTY_PRICE:
		    product.setPrice(rs.getDouble("value"));
		    // System.out.println("Setting Price");
		    break;
		case InventoryConstants.PROPERTY_TAGS:
		    List<Category> categoriesList = this
			    .convertTagsToCategories(rs.getString("value"));
		    if (categoriesList != null)
			product.setCategories(categoriesList);
		    break;
		case InventoryConstants.PROPERTY_WEIGHT:
		    product.setWeight(rs.getDouble("value") * 2.2);
		    // System.out.println("Setting Weight");
		    break;
		}
	    }

	    /*
	     * Set stock and determine if product should be marked as in stock
	     * or not
	     */
	    product.setQty(this.getProductQuantity(id));

	    // product.setMedias(this.getProductImages(id));
	    product.setEnabled(true);
	    Integer[] websites = { 1 };
	    product.setWebsites(websites);

	} catch (SQLException e) {
	    e.printStackTrace();
	}
	return product;
    }

    /**
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
		System.out.println("Adding image to product length:"
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
		    System.out.println("Only read " + offset
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

    private void verifyConfigurableProduct(int posProductId) {
	try {

	    /* Pull sku from product so we know what we're dealing with */
	    Product parentProduct = null;
	    String stmt = "SELECT value FROM metavalue WHERE id_item = ? AND ID_METATYPE = ?";
	    PreparedStatement p = conn.prepareStatement(stmt);
	    p.setInt(1, posProductId);
	    p.setInt(2, InventoryConstants.PROPERTY_CODE);

	    ResultSet rs = p.executeQuery();

	    while (rs.next()) {
		try {
		    /*
		     * Check configurable product SKU ID - see if we can find it
		     * within Magento
		     */
		    String sku = rs.getString("value");
		    if (configurableProductsAdded.contains(sku))
			return;
		    // System.out.println("TRYING TO GET: " + sku);
		    parentProduct = productService.getBySku(sku);
		} catch (ServiceException e) {
		    e.printStackTrace();
		}
	    }

	    if (parentProduct != null
		    && parentProduct.getType() != ProductTypeEnum.CONFIGURABLE
			    .getProductType()
		    && !configurableProductsAdded.contains(parentProduct
			    .getSku())) {
		System.out
			.println("^^^ PRODUCT IS A PARENT - SETTING TO CONFIGURABLE");

		productService.setProductTypeConfigurable(parentProduct);
		configurableProductsAdded.add(parentProduct.getSku());
		/*
		 * We found a configurable product, set the super attributes of
		 * it
		 */
		determineAndSetSuperAttribute(parentProduct, posProductId);
	    }

	} catch (ServiceException e) {
	    e.printStackTrace();
	} catch (SQLException e) {
	    e.printStackTrace();
	}
    }

    private void determineAndSetSuperAttribute(Product product, int posProductId) {
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
		ProductAttribute attribute = attributeService.getByCode(rs
			.getString("description"));

		if (attribute != null) {
		    // attributeService.getOptions(attribute);
		    // for (Map.Entry<Integer, String> opt :
		    // attribute.getOptions().entrySet())
		    // System.out.println(opt.toString());
		    productService.setSuperAttribute(product, attribute);
		}
	    }

	} catch (SQLException e) {
	    e.printStackTrace();
	} catch (ServiceException e) {
	    e.printStackTrace();
	}
    }

    private void setParentLink(Product childProduct, int posProductId) {
	try {
	    Product parentProduct = null;
	    String stmt = "SELECT value FROM metavalue WHERE id_item = ? AND ID_METATYPE = ?";
	    PreparedStatement p = conn.prepareStatement(stmt);
	    p.setInt(1, posProductId);
	    p.setInt(2, InventoryConstants.PROPERTY_CODE);

	    ResultSet rs = p.executeQuery();

	    while (rs.next()) {
		String sku = rs.getString("value");
		parentProduct = productService.getBySku(sku);
	    }

	    productLinkService.assignSimpleToConfigurable(childProduct,
		    parentProduct);

	} catch (ServiceException e) {
	    e.printStackTrace();
	} catch (SQLException e) {
	    e.printStackTrace();
	}
    }

    private void setProductAttribute(int posProductId, Product product) {
	try {
	    String stmt = "select c.description AS super_attribute, p.description AS attribute "
		    + "from permutation p, characteristic c where p.id = ("
		    + "select id_permutation from item_permutation where id_item = ?)"
		    + "and p.id_characteristic = c.id";
	    PreparedStatement p = conn.prepareStatement(stmt);
	    p.setInt(1, posProductId);
	    ResultSet rs = p.executeQuery();

	    while (rs.next()) {
		//System.out.println(rs.getString("super_attribute") + ' '
		//	+ rs.getString("attribute"));
		
		ProductAttribute attribute = attributeService.getByCode(rs.getString("super_attribute"));
		attributeService.getOptions(attribute);
		for (Map.Entry<Integer, String> opt : attribute.getOptions().entrySet())
		{
		    String magentoAttribute = rs.getString("attribute").toLowerCase();
		    String posAttribute = opt.toString().toLowerCase().substring(2);
		    
			if (posAttribute.equals(magentoAttribute))
			{
				System.out.println("We Match");
			}
		}
	    }
	} catch (SQLException e) {
	    e.printStackTrace();
	} catch (ServiceException e) {
	    e.printStackTrace();
	}

    }

}
