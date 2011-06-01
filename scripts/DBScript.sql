--Create Column to determine out of sync items
ALTER TABLE item ADD COLUMN sync_status text DEFAULT 'IN_SYNC';

-- Notification Function
CREATE OR REPLACE FUNCTION inventory_change() RETURNS trigger AS $inventory_change$
    BEGIN
	    IF (TG_OP = 'UPDATE') THEN 	

	        --Check if inventory has been deleted
            IF (OLD.deleted != NEW.deleted) THEN
            	NOTIFY productDeleted;
            	NEW.sync_status := 'OUT_OF_SYNC_DELETE';
    
            --Check if inventory quantity changed
        	ELSIF (OLD.sortkey_number3 != NEW.sortkey_number3) THEN
            	NOTIFY productUpdatedQuantity;
            	NEW.sync_status := 'OUT_OF_SYNC_UPDATE';            
            	
            --Check if inventory price changed
        	ELSIF (OLD.sortkey_number1 != NEW.sortkey_number1) THEN
            	NOTIFY productUpdatedPrice;
            	NEW.sync_status := 'OUT_OF_SYNC_UPDATE';
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

-- Product Added
-- Trigger when INSERT occurs on the ITEM table
DROP TRIGGER inventory_changed ON item;
CREATE TRIGGER inventory_changed BEFORE INSERT OR UPDATE ON item
    FOR EACH ROW EXECUTE PROCEDURE inventory_change();
    


