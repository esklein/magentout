--Create Column to determine out of sync items
ALTER TABLE item ADD COLUMN sync_status text DEFAULT 'IN_SYNC';
ALTER TABLE item ADD COLUMN sync_id integer;

-- Notification Function
CREATE OR REPLACE FUNCTION inventory_change() RETURNS trigger AS $inventory_change$
    BEGIN
	    IF (TG_OP = 'UPDATE') THEN 	

	        --Check if inventory has been deleted
            IF (OLD.deleted != NEW.deleted) THEN
            	NOTIFY productUpdated;
            	NEW.sync_status := 'OUT_OF_SYNC_DELETE';
    
            --Check if inventory quantity changed
        	-- ELSIF (OLD.sortkey_number3 != NEW.sortkey_number3) THEN
            --	NOTIFY productUpdatedQuantity;
            --	IF (OLD.sync_status != 'OUT_OF_SYNC_INSERT') THEN
            --		NEW.sync_status := 'OUT_OF_SYNC_UPDATE';          
            --	END IF;
            	
            --Check if inventory price changed
        	ELSIF (OLD.sortkey_number1 != NEW.sortkey_number1) THEN
            	NOTIFY productUpdated;
            	IF (OLD.sync_status != 'OUT_OF_SYNC_INSERT') THEN
            		NEW.sync_status := 'OUT_OF_SYNC_UPDATE';          
            	END IF;
            END IF;
            RETURN NEW;
            
        ELSIF (TG_OP = 'INSERT') THEN
            NOTIFY productAdded;
            --Set inital insert inventory 0.0 otherwise when checking inital inventory NULL won't compare to a number
            NEW.sortkey_number3 := '0.0';
            NEW.sync_status := 'OUT_OF_SYNC_INSERT';
            RETURN NEW;
        END IF;
        RETURN NULL; 
    END;
$inventory_change$ LANGUAGE plpgsql;


-- Notification Function
CREATE OR REPLACE FUNCTION qty_change() RETURNS trigger AS $qty_change$
    BEGIN
	    IF (OLD.quantity != NEW.quantity) THEN
	     UPDATE item SET sync_status = 'OUT_OF_SYNC_UPDATE' WHERE id = NEW.id_product;
	     NOTIFY productUpdated;
	    END IF;
	    RETURN NEW;
    END;
$qty_change$ LANGUAGE plpgsql;
-- Product Added
-- Trigger when INSERT occurs on the ITEM table
DROP TRIGGER inventory_changed ON item;
CREATE TRIGGER inventory_changed BEFORE INSERT OR UPDATE ON item
    FOR EACH ROW EXECUTE PROCEDURE inventory_change();
    
DROP TRIGGER qty_changed ON stock;
CREATE TRIGGER qty_changed BEFORE UPDATE ON stock
    FOR EACH ROW EXECUTE PROCEDURE qty_change();
    
    


