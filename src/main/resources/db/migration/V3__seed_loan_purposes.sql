-- The loan purposes the service starts with. Descriptions are given to the model as context, so they say what the
-- loan finances and which documents usually come with it.
INSERT INTO document_reader.loan_purpose (code, name, description) VALUES
    ('BOAT_PURCHASE', 'Boat purchase',
     'A loan to buy a boat; expect hull or serial number records, a marine survey, title, registration and proof of insurance.'),
    ('BOAT_REFINANCE', 'Boat refinance',
     'A loan replacing an existing boat loan; expect the current payoff statement, title, registration and proof of insurance.'),
    ('CAR_PURCHASE', 'Car purchase',
     'A loan to buy a car; expect a buyer''s order or bill of sale, the VIN, odometer reading, title and proof of insurance.'),
    ('CAR_REFINANCE', 'Car refinance',
     'A loan replacing an existing car loan; expect the current payoff statement, registration, title and proof of insurance.'),
    ('HOME_PURCHASE', 'Home purchase',
     'A mortgage to buy a home; expect the purchase agreement, appraisal, loan estimate, closing disclosure and income records.'),
    ('MORTGAGE_REFINANCE', 'Mortgage refinance',
     'A mortgage replacing an existing one; expect the payoff statement, appraisal, net tangible benefit analysis and income records.'),
    ('RV_PURCHASE', 'RV purchase',
     'A loan to buy a recreational vehicle; expect a buyer''s order, the VIN, valuation report, title and proof of insurance.'),
    ('RV_REFINANCE', 'RV refinance',
     'A loan replacing an existing recreational vehicle loan; expect the payoff statement, registration, title and proof of insurance.');
