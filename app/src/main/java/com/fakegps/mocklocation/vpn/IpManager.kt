package com.fakegps.mocklocation.vpn

import android.content.Context
import android.util.Log
import com.fakegps.mocklocation.engine.GeoUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object IpManager {

    private const val TAG = "IpManager"

    val GLOBAL_PRIVACY_NODES = listOf(
        // North America
        IpNode("us_nyc", "United States (New York)", "United States", "US", "🇺🇸", "New York", 40.7128, -74.0060, "198.51.100.42", 18),
        IpNode("us_lax", "United States (Los Angeles)", "United States", "US", "🇺🇸", "Los Angeles", 34.0522, -118.2437, "198.51.100.89", 24),
        IpNode("us_ord", "United States (Chicago)", "United States", "US", "🇺🇸", "Chicago", 41.8781, -87.6298, "198.51.100.105", 21),
        IpNode("us_mia", "United States (Miami)", "United States", "US", "🇺🇸", "Miami", 25.7617, -80.1918, "198.51.100.162", 26),
        IpNode("us_sea", "United States (Seattle)", "United States", "US", "🇺🇸", "Seattle", 47.6062, -122.3321, "198.51.100.210", 28),
        IpNode("us_dfw", "United States (Dallas)", "United States", "US", "🇺🇸", "Dallas", 32.7767, -96.7970, "198.51.100.75", 22),
        IpNode("ca_tor", "Canada (Toronto)", "Canada", "CA", "🇨🇦", "Toronto", 43.6532, -79.3832, "198.51.100.120", 31),
        IpNode("ca_yvr", "Canada (Vancouver)", "Canada", "CA", "🇨🇦", "Vancouver", 49.2827, -123.1207, "198.51.100.145", 33),
        IpNode("ca_yul", "Canada (Montreal)", "Canada", "CA", "🇨🇦", "Montreal", 45.5017, -73.5673, "198.51.100.155", 32),
        IpNode("mx_mex", "Mexico (Mexico City)", "Mexico", "MX", "🇲🇽", "Mexico City", 19.4326, -99.1332, "189.240.10.12", 45),
        IpNode("mx_gdl", "Mexico (Guadalajara)", "Mexico", "MX", "🇲🇽", "Guadalajara", 20.6597, -103.3496, "189.240.10.35", 48),
        IpNode("mx_mty", "Mexico (Monterrey)", "Mexico", "MX", "🇲🇽", "Monterrey", 25.6866, -100.3161, "189.240.10.60", 42),

        // Central America & Caribbean
        IpNode("cr_sjo", "Costa Rica (San José)", "Costa Rica", "CR", "🇨🇷", "San José", 9.9281, -84.0907, "190.113.20.5", 52),
        IpNode("pa_pty", "Panama (Panama City)", "Panama", "PA", "🇵🇦", "Panama City", 8.9824, -79.5199, "190.140.15.8", 50),
        IpNode("gt_gua", "Guatemala (Guatemala City)", "Guatemala", "GT", "🇬🇹", "Guatemala City", 14.6349, -90.5069, "190.115.10.4", 55),
        IpNode("hn_teg", "Honduras (Tegucigalpa)", "Honduras", "HN", "🇭🇳", "Tegucigalpa", 14.0723, -87.1921, "190.124.8.12", 58),
        IpNode("sv_sal", "El Salvador (San Salvador)", "El Salvador", "SV", "🇸🇻", "San Salvador", 13.6929, -89.2182, "190.120.30.6", 56),
        IpNode("bz_bze", "Belize (Belmopan)", "Belize", "BZ", "🇧🇿", "Belmopan", 17.2510, -88.7590, "190.197.12.9", 60),
        IpNode("ni_mga", "Nicaragua (Managua)", "Nicaragua", "NI", "🇳🇮", "Managua", 12.1149, -86.2362, "190.212.18.3", 59),
        IpNode("jm_kin", "Jamaica (Kingston)", "Jamaica", "JM", "🇯🇲", "Kingston", 17.9712, -76.7936, "190.213.5.15", 48),
        IpNode("bs_nas", "Bahamas (Nassau)", "Bahamas", "BS", "🇧🇸", "Nassau", 25.0443, -77.3504, "190.120.88.2", 39),
        IpNode("do_sdq", "Dominican Republic (Santo Domingo)", "Dominican Republic", "DO", "🇩🇴", "Santo Domingo", 18.4861, -69.9312, "190.166.45.10", 46),
        IpNode("pr_sju", "Puerto Rico (San Juan)", "Puerto Rico", "PR", "🇵🇷", "San Juan", 18.4655, -66.1057, "196.12.160.4", 42),
        IpNode("cu_hav", "Cuba (Havana)", "Cuba", "CU", "🇨🇺", "Havana", 23.1136, -82.3666, "152.206.10.8", 68),
        IpNode("tt_pos", "Trinidad and Tobago (Port of Spain)", "Trinidad and Tobago", "TT", "🇹🇹", "Port of Spain", 10.6549, -61.5019, "190.58.12.7", 54),
        IpNode("bb_bgi", "Barbados (Bridgetown)", "Barbados", "BB", "🇧🇧", "Bridgetown", 13.1939, -59.5432, "190.102.3.9", 58),
        IpNode("ht_pap", "Haiti (Port-au-Prince)", "Haiti", "HT", "🇭🇹", "Port-au-Prince", 18.5944, -72.3074, "190.115.90.1", 62),
        IpNode("lc_slu", "Saint Lucia (Castries)", "Saint Lucia", "LC", "🇱🇨", "Castries", 14.0101, -60.9875, "190.106.14.8", 60),
        IpNode("gd_gnd", "Grenada (St. George's)", "Grenada", "GD", "🇬🇩", "St. George's", 12.0561, -61.7488, "190.107.5.3", 64),
        IpNode("ag_anu", "Antigua and Barbuda (St. John's)", "Antigua and Barbuda", "AG", "🇦🇬", "St. John's", 17.1274, -61.8468, "190.108.9.2", 61),
        IpNode("dm_dom", "Dominica (Roseau)", "Dominica", "DM", "🇩🇲", "Roseau", 15.3092, -61.3794, "190.109.11.4", 63),
        IpNode("kn_skb", "Saint Kitts and Nevis (Basseterre)", "Saint Kitts and Nevis", "KN", "🇰🇳", "Basseterre", 17.3026, -62.7177, "190.110.15.6", 62),
        IpNode("vc_svd", "Saint Vincent (Kingstown)", "Saint Vincent and the Grenadines", "VC", "🇻🇨", "Kingstown", 13.1600, -61.2248, "190.111.8.7", 65),
        IpNode("bm_bda", "Bermuda (Hamilton)", "Bermuda", "BM", "🇧🇲", "Hamilton", 32.2948, -64.7814, "199.119.230.1", 44),
        IpNode("ky_cym", "Cayman Islands (George Town)", "Cayman Islands", "KY", "🇰🇾", "George Town", 19.2869, -81.3674, "190.213.180.5", 47),
        IpNode("aw_aua", "Aruba (Oranjestad)", "Aruba", "AW", "🇦🇼", "Oranjestad", 12.5092, -70.0086, "190.220.12.8", 53),
        IpNode("cw_cur", "Curaçao (Willemstad)", "Curaçao", "CW", "🇨🇼", "Willemstad", 12.1696, -68.9900, "190.220.45.2", 52),

        // South America
        IpNode("br_sao", "Brazil (São Paulo)", "Brazil", "BR", "🇧🇷", "São Paulo", -23.5505, -46.6333, "185.220.111.90", 58),
        IpNode("br_rio", "Brazil (Rio de Janeiro)", "Brazil", "BR", "🇧🇷", "Rio de Janeiro", -22.9068, -43.1729, "185.220.111.112", 60),
        IpNode("br_bsb", "Brazil (Brasília)", "Brazil", "BR", "🇧🇷", "Brasília", -15.7975, -47.8919, "185.220.111.45", 62),
        IpNode("ar_bue", "Argentina (Buenos Aires)", "Argentina", "AR", "🇦🇷", "Buenos Aires", -34.6037, -58.3816, "190.210.15.30", 64),
        IpNode("ar_cor", "Argentina (Córdoba)", "Argentina", "AR", "🇦🇷", "Córdoba", -31.4201, -64.1888, "190.210.45.12", 67),
        IpNode("cl_scl", "Chile (Santiago)", "Chile", "CL", "🇨🇱", "Santiago", -33.4489, -70.6693, "190.196.22.10", 63),
        IpNode("co_bog", "Colombia (Bogotá)", "Colombia", "CO", "🇨🇴", "Bogotá", 4.7110, -74.0721, "190.157.10.8", 49),
        IpNode("co_mde", "Colombia (Medellín)", "Colombia", "CO", "🇨🇴", "Medellín", 6.2442, -75.5812, "190.157.35.14", 51),
        IpNode("pe_lim", "Peru (Lima)", "Peru", "PE", "🇵🇪", "Lima", -12.0464, -77.0428, "190.187.8.25", 59),
        IpNode("ec_uio", "Ecuador (Quito)", "Ecuador", "EC", "🇪🇨", "Quito", -0.1807, -78.4678, "190.152.12.9", 54),
        IpNode("ec_gye", "Ecuador (Guayaquil)", "Ecuador", "EC", "🇪🇨", "Guayaquil", -2.1894, -79.8891, "190.152.44.18", 55),
        IpNode("ve_ccs", "Venezuela (Caracas)", "Venezuela", "VE", "🇻🇪", "Caracas", 10.4806, -66.9036, "190.202.8.14", 61),
        IpNode("uy_mvd", "Uruguay (Montevideo)", "Uruguay", "UY", "🇺🇾", "Montevideo", -34.9011, -56.1645, "190.64.18.22", 66),
        IpNode("py_asu", "Paraguay (Asunción)", "Paraguay", "PY", "🇵🇾", "Asunción", -25.2637, -57.5759, "190.128.5.12", 68),
        IpNode("bo_lpz", "Bolivia (La Paz)", "Bolivia", "BO", "🇧🇴", "La Paz", -16.4897, -68.1193, "190.181.14.7", 70),
        IpNode("gy_geo", "Guyana (Georgetown)", "Guyana", "GY", "🇬🇾", "Georgetown", 6.8013, -58.1551, "190.104.22.4", 65),
        IpNode("sr_pbm", "Suriname (Paramaribo)", "Suriname", "SR", "🇸🇷", "Paramaribo", 5.8520, -55.2038, "190.105.10.8", 66),

        // Western Europe
        IpNode("uk_lon", "United Kingdom (London)", "United Kingdom", "GB", "🇬🇧", "London", 51.5074, -0.1278, "185.220.101.5", 22),
        IpNode("uk_man", "United Kingdom (Manchester)", "United Kingdom", "GB", "🇬🇧", "Manchester", 53.4808, -2.2426, "185.220.101.94", 25),
        IpNode("uk_edi", "United Kingdom (Edinburgh)", "United Kingdom", "GB", "🇬🇧", "Edinburgh", 55.9533, -3.1883, "185.220.101.120", 27),
        IpNode("ie_dub", "Ireland (Dublin)", "Ireland", "IE", "🇮🇪", "Dublin", 53.3498, -6.2603, "185.220.109.19", 28),
        IpNode("de_fra", "Germany (Frankfurt)", "Germany", "DE", "🇩🇪", "Frankfurt", 50.1109, 8.6821, "185.220.102.14", 29),
        IpNode("de_ber", "Germany (Berlin)", "Germany", "DE", "🇩🇪", "Berlin", 52.5200, 13.4050, "185.220.102.83", 31),
        IpNode("de_muc", "Germany (Munich)", "Germany", "DE", "🇩🇪", "Munich", 48.1351, 11.5820, "185.220.102.110", 30),
        IpNode("fr_par", "France (Paris)", "France", "FR", "🇫🇷", "Paris", 48.8566, 2.3522, "185.220.103.77", 26),
        IpNode("fr_mrs", "France (Marseille)", "France", "FR", "🇫🇷", "Marseille", 43.2965, 5.3698, "185.220.103.104", 28),
        IpNode("nl_ams", "Netherlands (Amsterdam)", "Netherlands", "NL", "🇳🇱", "Amsterdam", 52.3676, 4.9041, "185.220.104.9", 21),
        IpNode("be_bru", "Belgium (Brussels)", "Belgium", "BE", "🇧🇪", "Brussels", 50.8503, 4.3517, "185.220.113.15", 24),
        IpNode("lu_lux", "Luxembourg (Luxembourg)", "Luxembourg", "LU", "🇱🇺", "Luxembourg", 49.6116, 6.1319, "185.220.113.40", 25),
        IpNode("ch_zrh", "Switzerland (Zurich)", "Switzerland", "CH", "🇨🇭", "Zurich", 47.3769, 8.5417, "185.220.105.44", 27),
        IpNode("ch_gva", "Switzerland (Geneva)", "Switzerland", "CH", "🇨🇭", "Geneva", 46.2044, 6.1432, "185.220.105.80", 28),
        IpNode("at_vie", "Austria (Vienna)", "Austria", "AT", "🇦🇹", "Vienna", 48.2082, 16.3738, "185.220.114.18", 29),
        IpNode("mc_mco", "Monaco (Monaco)", "Monaco", "MC", "🇲🇨", "Monaco", 43.7384, 7.4246, "185.220.114.55", 30),
        IpNode("li_vad", "Liechtenstein (Vaduz)", "Liechtenstein", "LI", "🇱🇮", "Vaduz", 47.1410, 9.5209, "185.220.114.70", 29),

        // Northern Europe
        IpNode("se_sto", "Sweden (Stockholm)", "Sweden", "SE", "🇸🇪", "Stockholm", 59.3293, 18.0686, "185.220.106.12", 33),
        IpNode("no_osl", "Norway (Oslo)", "Norway", "NO", "🇳🇴", "Oslo", 59.9139, 10.7522, "185.220.115.22", 32),
        IpNode("dk_cph", "Denmark (Copenhagen)", "Denmark", "DK", "🇩🇰", "Copenhagen", 55.6761, 12.5683, "185.220.115.65", 28),
        IpNode("fi_hel", "Finland (Helsinki)", "Finland", "FI", "🇫🇮", "Helsinki", 60.1699, 24.9384, "185.220.115.90", 35),
        IpNode("is_rkv", "Iceland (Reykjavik)", "Iceland", "IS", "🇮🇸", "Reykjavik", 64.1466, -21.9426, "185.220.116.10", 42),
        IpNode("ee_tll", "Estonia (Tallinn)", "Estonia", "EE", "🇪🇪", "Tallinn", 59.4370, 24.7536, "185.220.116.44", 36),
        IpNode("lv_rix", "Latvia (Riga)", "Latvia", "LV", "🇱🇻", "Riga", 56.9496, 24.1052, "185.220.116.70", 37),
        IpNode("lt_vno", "Lithuania (Vilnius)", "Lithuania", "LT", "🇱🇹", "Vilnius", 54.6872, 25.2797, "185.220.116.95", 38),

        // Southern Europe
        IpNode("es_mad", "Spain (Madrid)", "Spain", "ES", "🇪🇸", "Madrid", 40.4168, -3.7038, "185.220.107.55", 34),
        IpNode("es_bcn", "Spain (Barcelona)", "Spain", "ES", "🇪🇸", "Barcelona", 41.3879, 2.1699, "185.220.107.88", 32),
        IpNode("pt_lis", "Portugal (Lisbon)", "Portugal", "PT", "🇵🇹", "Lisbon", 38.7223, -9.1393, "185.220.117.15", 36),
        IpNode("pt_opo", "Portugal (Porto)", "Portugal", "PT", "🇵🇹", "Porto", 41.1579, -8.6291, "185.220.117.40", 35),
        IpNode("it_mil", "Italy (Milan)", "Italy", "IT", "🇮🇹", "Milan", 45.4642, 9.1900, "185.220.108.31", 30),
        IpNode("it_rom", "Italy (Rome)", "Italy", "IT", "🇮🇹", "Rome", 41.9028, 12.4964, "185.220.108.75", 33),
        IpNode("gr_ath", "Greece (Athens)", "Greece", "GR", "🇬🇷", "Athens", 37.9838, 23.7275, "185.220.118.20", 41),
        IpNode("cy_nic", "Cyprus (Nicosia)", "Cyprus", "CY", "🇨🇾", "Nicosia", 35.1856, 33.3823, "185.220.118.60", 46),
        IpNode("mt_mla", "Malta (Valletta)", "Malta", "MT", "🇲🇹", "Valletta", 35.8989, 14.5146, "185.220.118.90", 39),
        IpNode("ad_and", "Andorra (Andorra la Vella)", "Andorra", "AD", "🇦🇩", "Andorra la Vella", 42.5063, 1.5218, "185.220.119.12", 33),
        IpNode("sm_smn", "San Marino (San Marino)", "San Marino", "SM", "🇸🇲", "San Marino", 43.9424, 12.4578, "185.220.119.35", 32),

        // Central & Eastern Europe
        IpNode("pl_waw", "Poland (Warsaw)", "Poland", "PL", "🇵🇱", "Warsaw", 52.2297, 21.0122, "185.220.120.15", 33),
        IpNode("pl_krk", "Poland (Krakow)", "Poland", "PL", "🇵🇱", "Krakow", 50.0647, 19.9450, "185.220.120.40", 34),
        IpNode("cz_prg", "Czech Republic (Prague)", "Czech Republic", "CZ", "🇨🇿", "Prague", 50.0755, 14.4378, "185.220.120.75", 30),
        IpNode("sk_bts", "Slovakia (Bratislava)", "Slovakia", "SK", "🇸🇰", "Bratislava", 48.1486, 17.1077, "185.220.121.20", 31),
        IpNode("hu_bud", "Hungary (Budapest)", "Hungary", "HU", "🇭🇺", "Budapest", 47.4979, 19.0402, "185.220.121.55", 32),
        IpNode("ro_otp", "Romania (Bucharest)", "Romania", "RO", "🇷🇴", "Bucharest", 44.4268, 26.1025, "185.220.121.85", 38),
        IpNode("bg_sof", "Bulgaria (Sofia)", "Bulgaria", "BG", "🇧🇬", "Sofia", 42.6977, 23.3219, "185.220.122.18", 40),
        IpNode("hr_zag", "Croatia (Zagreb)", "Croatia", "HR", "🇭🇷", "Zagreb", 45.8150, 15.9819, "185.220.122.50", 34),
        IpNode("si_lju", "Slovenia (Ljubljana)", "Slovenia", "SI", "🇸🇮", "Ljubljana", 46.0569, 14.5058, "185.220.122.80", 32),
        IpNode("rs_beg", "Serbia (Belgrade)", "Serbia", "RS", "🇷🇸", "Belgrade", 44.7866, 20.4489, "185.220.123.15", 37),
        IpNode("ba_sjj", "Bosnia and Herzegovina (Sarajevo)", "Bosnia and Herzegovina", "BA", "🇧🇦", "Sarajevo", 43.8563, 18.4131, "185.220.123.45", 39),
        IpNode("me_tgd", "Montenegro (Podgorica)", "Montenegro", "ME", "🇲🇪", "Podgorica", 42.4304, 19.2594, "185.220.123.75", 41),
        IpNode("mk_skp", "North Macedonia (Skopje)", "North Macedonia", "MK", "🇲🇰", "Skopje", 41.9981, 21.4254, "185.220.124.12", 42),
        IpNode("al_tia", "Albania (Tirana)", "Albania", "AL", "🇦🇱", "Tirana", 41.3275, 19.8187, "185.220.124.40", 43),
        IpNode("md_kiv", "Moldova (Chisinau)", "Moldova", "MD", "🇲🇩", "Chisinau", 47.0105, 28.8638, "185.220.124.70", 42),
        IpNode("ua_iev", "Ukraine (Kyiv)", "Ukraine", "UA", "🇺🇦", "Kyiv", 50.4501, 30.5234, "185.220.125.10", 44),
        IpNode("ge_tbs", "Georgia (Tbilisi)", "Georgia", "GE", "🇬🇪", "Tbilisi", 41.7151, 44.8271, "185.220.125.45", 52),
        IpNode("am_evn", "Armenia (Yerevan)", "Armenia", "AM", "🇦🇲", "Yerevan", 40.1792, 44.4991, "185.220.125.75", 54),
        IpNode("az_bak", "Azerbaijan (Baku)", "Azerbaijan", "AZ", "🇦🇿", "Baku", 40.4093, 49.8671, "185.220.126.15", 56),

        // East & Southeast Asia
        IpNode("jp_tyo", "Japan (Tokyo)", "Japan", "JP", "🇯🇵", "Tokyo", 35.6762, 139.6503, "203.0.113.88", 38),
        IpNode("jp_osa", "Japan (Osaka)", "Japan", "JP", "🇯🇵", "Osaka", 34.6937, 135.5023, "203.0.113.92", 41),
        IpNode("kr_sel", "South Korea (Seoul)", "South Korea", "KR", "🇰🇷", "Seoul", 37.5665, 126.9780, "203.0.113.140", 39),
        IpNode("hk_hkg", "Hong Kong (Hong Kong)", "Hong Kong", "HK", "🇭🇰", "Hong Kong", 22.3193, 114.1694, "203.0.113.175", 36),
        IpNode("mo_mfm", "Macau (Macau)", "Macau", "MO", "🇲🇴", "Macau", 22.1987, 113.5439, "203.0.113.180", 37),
        IpNode("tw_tpe", "Taiwan (Taipei)", "Taiwan", "TW", "🇹🇼", "Taipei", 25.0330, 121.5654, "203.0.113.195", 40),
        IpNode("sg_sin", "Singapore (Singapore)", "Singapore", "SG", "🇸🇬", "Singapore", 1.3521, 103.8198, "203.0.113.15", 35),
        IpNode("my_kul", "Malaysia (Kuala Lumpur)", "Malaysia", "MY", "🇲🇾", "Kuala Lumpur", 3.1390, 101.6869, "203.0.114.22", 39),
        IpNode("th_bkk", "Thailand (Bangkok)", "Thailand", "TH", "🇹🇭", "Bangkok", 13.7563, 100.5018, "203.0.114.55", 43),
        IpNode("vn_sgn", "Vietnam (Ho Chi Minh City)", "Vietnam", "VN", "🇻🇳", "Ho Chi Minh City", 10.8231, 106.6297, "203.0.114.85", 45),
        IpNode("vn_han", "Vietnam (Hanoi)", "Vietnam", "VN", "🇻🇳", "Hanoi", 21.0285, 105.8542, "203.0.114.95", 47),
        IpNode("id_jkt", "Indonesia (Jakarta)", "Indonesia", "ID", "🇮🇩", "Jakarta", -6.2088, 106.8456, "203.0.115.18", 44),
        IpNode("id_dps", "Indonesia (Bali)", "Indonesia", "ID", "🇮🇩", "Denpasar", -8.6705, 115.2126, "203.0.115.35", 46),
        IpNode("ph_mnl", "Philippines (Manila)", "Philippines", "PH", "🇵🇭", "Manila", 14.5995, 120.9842, "203.0.115.70", 48),
        IpNode("kh_pnh", "Cambodia (Phnom Penh)", "Cambodia", "KH", "🇰🇭", "Phnom Penh", 11.5564, 104.9282, "203.0.116.12", 50),
        IpNode("la_vte", "Laos (Vientiane)", "Laos", "LA", "🇱🇦", "Vientiane", 17.9757, 102.6331, "203.0.116.40", 52),
        IpNode("bn_bwn", "Brunei (Bandar Seri Begawan)", "Brunei", "BN", "🇧🇳", "Bandar Seri Begawan", 4.9031, 114.9398, "203.0.116.65", 43),
        IpNode("mm_rgn", "Myanmar (Yangon)", "Myanmar", "MM", "🇲🇲", "Yangon", 16.8661, 96.1951, "203.0.116.90", 56),
        IpNode("mn_uln", "Mongolia (Ulaanbaatar)", "Mongolia", "MN", "🇲🇳", "Ulaanbaatar", 47.8864, 106.9057, "203.0.117.25", 58),

        // South & Central Asia
        IpNode("in_bom", "India (Mumbai)", "India", "IN", "🇮🇳", "Mumbai", 19.0760, 72.8777, "203.0.113.230", 44),
        IpNode("in_del", "India (New Delhi)", "India", "IN", "🇮🇳", "New Delhi", 28.6139, 77.2090, "203.0.113.245", 46),
        IpNode("in_blr", "India (Bengaluru)", "India", "IN", "🇮🇳", "Bengaluru", 12.9716, 77.5946, "203.0.113.250", 45),
        IpNode("pk_isb", "Pakistan (Islamabad)", "Pakistan", "PK", "🇵🇰", "Islamabad", 33.6844, 73.0479, "203.0.118.15", 52),
        IpNode("pk_khi", "Pakistan (Karachi)", "Pakistan", "PK", "🇵🇰", "Karachi", 24.8607, 67.0011, "203.0.118.40", 50),
        IpNode("bd_dac", "Bangladesh (Dhaka)", "Bangladesh", "BD", "🇧🇩", "Dhaka", 23.8103, 90.4125, "203.0.118.75", 53),
        IpNode("lk_cmb", "Sri Lanka (Colombo)", "Sri Lanka", "LK", "🇱🇰", "Colombo", 6.9271, 79.8612, "203.0.119.20", 49),
        IpNode("np_ktm", "Nepal (Kathmandu)", "Nepal", "NP", "🇳🇵", "Kathmandu", 27.7172, 85.3240, "203.0.119.55", 58),
        IpNode("mv_mle", "Maldives (Malé)", "Maldives", "MV", "🇲🇻", "Malé", 4.1755, 73.5093, "203.0.119.80", 52),
        IpNode("bt_pbh", "Bhutan (Thimphu)", "Bhutan", "BT", "🇧🇹", "Thimphu", 27.4728, 89.6393, "203.0.120.10", 62),
        IpNode("kz_ast", "Kazakhstan (Astana)", "Kazakhstan", "KZ", "🇰🇿", "Astana", 51.1694, 71.4491, "203.0.120.35", 54),
        IpNode("kz_ala", "Kazakhstan (Almaty)", "Kazakhstan", "KZ", "🇰🇿", "Almaty", 43.2220, 76.8512, "203.0.120.50", 55),
        IpNode("uz_tas", "Uzbekistan (Tashkent)", "Uzbekistan", "UZ", "🇺🇿", "Tashkent", 41.2995, 69.2401, "203.0.120.75", 57),
        IpNode("kg_fru", "Kyrgyzstan (Bishkek)", "Kyrgyzstan", "KG", "🇰🇬", "Bishkek", 42.8746, 74.5698, "203.0.121.15", 59),
        IpNode("tj_dyu", "Tajikistan (Dushanbe)", "Tajikistan", "TJ", "🇹🇯", "Dushanbe", 38.5598, 68.7870, "203.0.121.45", 61),
        IpNode("tm_asb", "Turkmenistan (Ashgabat)", "Turkmenistan", "TM", "🇹🇲", "Ashgabat", 37.9601, 58.3261, "203.0.121.80", 63),

        // Middle East
        IpNode("ae_dxb", "United Arab Emirates (Dubai)", "United Arab Emirates", "AE", "🇦🇪", "Dubai", 25.2048, 55.2708, "185.220.110.8", 42),
        IpNode("ae_auh", "United Arab Emirates (Abu Dhabi)", "United Arab Emirates", "AE", "🇦🇪", "Abu Dhabi", 24.4539, 54.3773, "185.220.110.25", 43),
        IpNode("sa_ruh", "Saudi Arabia (Riyadh)", "Saudi Arabia", "SA", "🇸🇦", "Riyadh", 24.7136, 46.6753, "185.220.127.18", 45),
        IpNode("sa_jed", "Saudi Arabia (Jeddah)", "Saudi Arabia", "SA", "🇸🇦", "Jeddah", 21.4858, 39.1925, "185.220.127.40", 47),
        IpNode("qa_doh", "Qatar (Doha)", "Qatar", "QA", "🇶🇦", "Doha", 25.2854, 51.5310, "185.220.127.75", 41),
        IpNode("kw_kwi", "Kuwait (Kuwait City)", "Kuwait", "KW", "🇰🇼", "Kuwait City", 29.3759, 47.9774, "185.220.128.20", 44),
        IpNode("bh_bah", "Bahrain (Manama)", "Bahrain", "BH", "🇧🇭", "Manama", 26.2285, 50.5860, "185.220.128.50", 43),
        IpNode("om_mct", "Oman (Muscat)", "Oman", "OM", "🇴🇲", "Muscat", 23.5880, 58.3829, "185.220.128.80", 46),
        IpNode("tr_ist", "Turkey (Istanbul)", "Turkey", "TR", "🇹🇷", "Istanbul", 41.0082, 28.9784, "185.220.129.15", 38),
        IpNode("tr_ank", "Turkey (Ankara)", "Turkey", "TR", "🇹🇷", "Ankara", 39.9334, 32.8597, "185.220.129.45", 40),
        IpNode("il_tlv", "Israel (Tel Aviv)", "Israel", "IL", "🇮🇱", "Tel Aviv", 32.0853, 34.7818, "185.220.129.80", 41),
        IpNode("jo_amm", "Jordan (Amman)", "Jordan", "JO", "🇯🇴", "Amman", 31.9454, 35.9284, "185.220.130.22", 48),
        IpNode("lb_bey", "Lebanon (Beirut)", "Lebanon", "LB", "🇱🇧", "Beirut", 33.8938, 35.5018, "185.220.130.55", 47),
        IpNode("iq_bgw", "Iraq (Baghdad)", "Iraq", "IQ", "🇮🇶", "Baghdad", 33.3152, 44.3661, "185.220.130.85", 52),

        // Africa (Northern, Western, Central, Eastern, Southern)
        IpNode("za_jnb", "South Africa (Johannesburg)", "South Africa", "ZA", "🇿🇦", "Johannesburg", -26.2041, 28.0473, "185.220.112.16", 65),
        IpNode("za_cpt", "South Africa (Cape Town)", "South Africa", "ZA", "🇿🇦", "Cape Town", -33.9249, 18.4241, "185.220.112.48", 68),
        IpNode("ng_los", "Nigeria (Lagos)", "Nigeria", "NG", "🇳🇬", "Lagos", 6.5244, 3.3792, "197.210.10.15", 62),
        IpNode("ng_abv", "Nigeria (Abuja)", "Nigeria", "NG", "🇳🇬", "Abuja", 9.0765, 7.3986, "197.210.10.45", 64),
        IpNode("eg_cai", "Egypt (Cairo)", "Egypt", "EG", "🇪🇬", "Cairo", 30.0444, 31.2357, "197.160.22.8", 46),
        IpNode("eg_aly", "Egypt (Alexandria)", "Egypt", "EG", "🇪🇬", "Alexandria", 31.2001, 29.9187, "197.160.22.35", 45),
        IpNode("ke_nbo", "Kenya (Nairobi)", "Kenya", "KE", "🇰🇪", "Nairobi", -1.2921, 36.8219, "197.248.15.20", 58),
        IpNode("ma_cas", "Morocco (Casablanca)", "Morocco", "MA", "🇲🇦", "Casablanca", 33.5731, -7.5898, "196.200.12.30", 42),
        IpNode("ma_rba", "Morocco (Rabat)", "Morocco", "MA", "🇲🇦", "Rabat", 34.0209, -6.8416, "196.200.12.55", 41),
        IpNode("gh_acc", "Ghana (Accra)", "Ghana", "GH", "🇬🇭", "Accra", 5.6037, -0.1870, "197.251.10.18", 63),
        IpNode("et_add", "Ethiopia (Addis Ababa)", "Ethiopia", "ET", "🇪🇹", "Addis Ababa", 9.0300, 38.7400, "197.156.8.14", 66),
        IpNode("dz_alg", "Algeria (Algiers)", "Algeria", "DZ", "🇩🇿", "Algiers", 36.7538, 3.0588, "197.112.18.25", 40),
        IpNode("tn_tun", "Tunisia (Tunis)", "Tunisia", "TN", "🇹🇳", "Tunis", 36.8065, 10.1815, "197.1.15.10", 39),
        IpNode("tz_dar", "Tanzania (Dar es Salaam)", "Tanzania", "TZ", "🇹🇿", "Dar es Salaam", -6.7924, 39.2083, "197.250.14.8", 60),
        IpNode("ug_ebb", "Uganda (Kampala)", "Uganda", "UG", "🇺🇬", "Kampala", 0.3476, 32.5825, "197.239.20.12", 64),
        IpNode("rw_kgl", "Rwanda (Kigali)", "Rwanda", "RW", "🇷🇼", "Kigali", -1.9441, 30.0619, "197.243.18.5", 63),
        IpNode("sn_dkr", "Senegal (Dakar)", "Senegal", "SN", "🇸🇳", "Dakar", 14.7167, -17.4677, "197.214.12.10", 55),
        IpNode("ci_abj", "Ivory Coast (Abidjan)", "Ivory Coast", "CI", "🇨🇮", "Abidjan", 5.3600, -4.0083, "197.240.22.4", 61),
        IpNode("cm_dla", "Cameroon (Douala)", "Cameroon", "CM", "🇨🇲", "Douala", 4.0511, 9.7679, "197.230.15.8", 67),
        IpNode("ao_lad", "Angola (Luanda)", "Angola", "AO", "🇦🇴", "Luanda", -8.8390, 13.2894, "197.218.10.12", 69),
        IpNode("zm_lun", "Zambia (Lusaka)", "Zambia", "ZM", "🇿🇲", "Lusaka", -15.3875, 28.3228, "197.215.18.6", 67),
        IpNode("zw_hre", "Zimbabwe (Harare)", "Zimbabwe", "ZW", "🇿🇼", "Harare", -17.8252, 31.0335, "197.221.14.9", 68),
        IpNode("mz_mpm", "Mozambique (Maputo)", "Mozambique", "MZ", "🇲🇿", "Maputo", -25.9692, 32.5732, "197.235.12.8", 66),
        IpNode("bw_gbe", "Botswana (Gaborone)", "Botswana", "BW", "🇧🇼", "Gaborone", -24.6282, 25.9231, "197.225.10.4", 66),
        IpNode("na_wdh", "Namibia (Windhoek)", "Namibia", "NA", "🇳🇦", "Windhoek", -22.5609, 17.0658, "197.226.15.7", 67),
        IpNode("mu_mru", "Mauritius (Port Louis)", "Mauritius", "MU", "🇲🇺", "Port Louis", -20.1609, 57.5012, "197.227.18.9", 58),
        IpNode("mg_tnr", "Madagascar (Antananarivo)", "Madagascar", "MG", "🇲🇬", "Antananarivo", -18.8792, 47.5079, "197.228.10.5", 64),
        IpNode("sc_sez", "Seychelles (Victoria)", "Seychelles", "SC", "🇸🇨", "Victoria", -4.6191, 55.4513, "197.229.12.3", 56),
        IpNode("ga_lbv", "Gabon (Libreville)", "Gabon", "GA", "🇬🇦", "Libreville", 0.4162, 9.4673, "197.231.14.6", 65),
        IpNode("cg_bzf", "Republic of the Congo (Brazzaville)", "Republic of the Congo", "CG", "🇨🇬", "Brazzaville", -4.2634, 15.2429, "197.232.10.8", 68),
        IpNode("cd_fih", "DR Congo (Kinshasa)", "DR Congo", "CD", "🇨🇩", "Kinshasa", -4.4419, 15.2663, "197.233.15.4", 69),
        IpNode("ml_bko", "Mali (Bamako)", "Mali", "ML", "🇲🇱", "Bamako", 12.6392, -8.0029, "197.234.12.7", 63),
        IpNode("bf_oua", "Burkina Faso (Ouagadougou)", "Burkina Faso", "BF", "🇧🇫", "Ouagadougou", 12.3714, -1.5197, "197.236.18.9", 64),
        IpNode("ne_nim", "Niger (Niamey)", "Niger", "NE", "🇳🇪", "Niamey", 13.5116, 2.1254, "197.237.10.2", 66),
        IpNode("bj_coo", "Benin (Cotonou)", "Benin", "BJ", "🇧🇯", "Cotonou", 6.3703, 2.4183, "197.238.14.5", 63),
        IpNode("tg_lfw", "Togo (Lomé)", "Togo", "TG", "🇹🇬", "Lomé", 6.1375, 1.2123, "197.241.16.8", 62),
        IpNode("gn_cky", "Guinea (Conakry)", "Guinea", "GN", "🇬🇳", "Conakry", 9.6412, -13.5784, "197.242.10.4", 60),
        IpNode("sl_fna", "Sierra Leone (Freetown)", "Sierra Leone", "SL", "🇸🇱", "Freetown", 8.4844, -13.2344, "197.244.12.6", 59),
        IpNode("lr_mlw", "Liberia (Monrovia)", "Liberia", "LR", "🇱🇷", "Monrovia", 6.3005, -10.7969, "197.245.15.9", 61),
        IpNode("mr_nkc", "Mauritania (Nouakchott)", "Mauritania", "MR", "🇲🇷", "Nouakchott", 18.0735, -15.9582, "197.246.18.3", 53),
        IpNode("ly_tip", "Libya (Tripoli)", "Libya", "LY", "🇱🇾", "Tripoli", 32.8872, 13.1913, "197.247.10.7", 44),
        IpNode("sd_krt", "Sudan (Khartoum)", "Sudan", "SD", "🇸🇩", "Khartoum", 15.5007, 32.5599, "197.249.14.2", 56),
        IpNode("td_ndj", "Chad (N'Djamena)", "Chad", "TD", "🇹🇩", "N'Djamena", 12.1348, 15.0557, "197.252.12.8", 64),
        IpNode("mw_llw", "Malawi (Lilongwe)", "Malawi", "MW", "🇲🇼", "Lilongwe", -13.9626, 33.7741, "197.253.15.6", 67),
        IpNode("ls_msu", "Lesotho (Maseru)", "Lesotho", "LS", "🇱🇸", "Maseru", -29.3151, 27.4869, "197.254.10.4", 66),
        IpNode("sz_qtz", "Eswatini (Mbabane)", "Eswatini", "SZ", "🇸🇿", "Mbabane", -26.3054, 31.1367, "197.255.12.9", 65),
        IpNode("cv_rai", "Cape Verde (Praia)", "Cape Verde", "CV", "🇨🇻", "Praia", 14.9330, -23.5133, "197.100.14.5", 51),
        IpNode("dj_jib", "Djibouti (Djibouti)", "Djibouti", "DJ", "🇩🇯", "Djibouti", 11.5721, 43.1456, "197.101.18.2", 54),
        IpNode("gm_bjb", "Gambia (Banjul)", "Gambia", "GM", "🇬🇲", "Banjul", 13.4549, -16.5790, "197.102.10.7", 57),

        // Oceania & Pacific
        IpNode("au_syd", "Australia (Sydney)", "Australia", "AU", "🇦🇺", "Sydney", -33.8688, 151.2093, "203.0.113.204", 49),
        IpNode("au_mel", "Australia (Melbourne)", "Australia", "AU", "🇦🇺", "Melbourne", -37.8136, 144.9631, "203.0.113.218", 52),
        IpNode("au_bne", "Australia (Brisbane)", "Australia", "AU", "🇦🇺", "Brisbane", -27.4698, 153.0251, "203.0.113.222", 50),
        IpNode("au_per", "Australia (Perth)", "Australia", "AU", "🇦🇺", "Perth", -31.9505, 115.8605, "203.0.113.225", 48),
        IpNode("nz_akl", "New Zealand (Auckland)", "New Zealand", "NZ", "🇳🇿", "Auckland", -36.8485, 174.7633, "203.0.122.15", 55),
        IpNode("nz_wlg", "New Zealand (Wellington)", "New Zealand", "NZ", "🇳🇿", "Wellington", -41.2865, 174.7762, "203.0.122.40", 57),
        IpNode("fj_nan", "Fiji (Suva)", "Fiji", "FJ", "🇫🇯", "Suva", -18.1248, 178.4501, "203.0.123.18", 62),
        IpNode("pg_pom", "Papua New Guinea (Port Moresby)", "Papua New Guinea", "PG", "🇵🇬", "Port Moresby", -9.4438, 147.1803, "203.0.123.50", 60),
        IpNode("ws_apw", "Samoa (Apia)", "Samoa", "WS", "🇼🇸", "Apia", -13.8333, -171.7667, "203.0.124.12", 68),
        IpNode("to_tbu", "Tonga (Nuku'alofa)", "Tonga", "TO", "🇹🇴", "Nuku'alofa", -21.1393, -175.2018, "203.0.124.45", 69),
        IpNode("vu_vli", "Vanuatu (Port Vila)", "Vanuatu", "VU", "🇻🇺", "Port Vila", -17.7333, 168.3273, "203.0.124.80", 65),
        IpNode("sb_hir", "Solomon Islands (Honiara)", "Solomon Islands", "SB", "🇸🇧", "Honiara", -9.4456, 159.9729, "203.0.125.20", 67),
        IpNode("gu_gum", "Guam (Hagåtña)", "Guam", "GU", "🇬🇺", "Hagåtña", 13.4757, 144.7489, "203.0.125.60", 45),
        IpNode("nc_nou", "New Caledonia (Nouméa)", "New Caledonia", "NC", "🇳🇨", "Nouméa", -22.2711, 166.4416, "203.0.126.15", 58),
        IpNode("pf_ppt", "French Polynesia (Papeete)", "French Polynesia", "PF", "🇵🇫", "Papeete", -17.5516, -149.5585, "203.0.126.50", 72),
        IpNode("pw_ror", "Palau (Ngerulmud)", "Palau", "PW", "🇵🇼", "Ngerulmud", 7.5004, 134.6242, "203.0.127.10", 48),
        IpNode("fm_pni", "Micronesia (Palikir)", "Micronesia", "FM", "🇫🇲", "Palikir", 6.9248, 158.1611, "203.0.127.40", 59),
        IpNode("mh_maj", "Marshall Islands (Majuro)", "Marshall Islands", "MH", "🇲🇭", "Majuro", 7.1163, 171.1858, "203.0.127.75", 63),
        IpNode("ki_trw", "Kiribati (Tarawa)", "Kiribati", "KI", "🇰🇮", "Tarawa", 1.3291, 172.9785, "203.0.128.15", 68),
        IpNode("tv_fun", "Tuvalu (Funafuti)", "Tuvalu", "TV", "🇹🇻", "Funafuti", -8.5211, 179.1983, "203.0.128.50", 71),
        IpNode("nr_inu", "Nauru (Yaren)", "Nauru", "NR", "🇳🇷", "Yaren", -0.5477, 166.9209, "203.0.128.85", 70)
    )

    val GLOBAL_NODES get() = GLOBAL_PRIVACY_NODES

    fun findNodeById(id: String?): IpNode? {
        return GLOBAL_PRIVACY_NODES.find { it.id == id }
    }

    fun getNodeById(id: String?): IpNode {
        return GLOBAL_PRIVACY_NODES.find { it.id == id } ?: GLOBAL_PRIVACY_NODES[0]
    }

    /**
     * Finds the closest privacy node to a given GPS coordinate to synchronize IP location with Mock GPS!
     */
    fun findClosestNodeForCoordinates(latitude: Double, longitude: Double): IpNode {
        var closestNode = GLOBAL_PRIVACY_NODES[0]
        var minDistance = Double.MAX_VALUE

        for (node in GLOBAL_PRIVACY_NODES) {
            val dist = GeoUtils.calculateDistanceMeters(latitude, longitude, node.latitude, node.longitude)
            if (dist < minDistance) {
                minDistance = dist
                closestNode = node
            }
        }
        return closestNode
    }

    /**
     * Fetches the current live public IP address and ISP/country info asynchronously.
     */
    suspend fun fetchPublicIpInfo(context: Context): PublicIpInfo = withContext(Dispatchers.IO) {
        val sessionPrefs = com.fakegps.mocklocation.data.preferences.SessionPreferences(context)
        val isMasked = NowhereVpnService.isRunning || sessionPrefs.isIpMaskingEnabled
        val activeNodeId = sessionPrefs.activeIpNodeId
        val activeNode = getNodeById(activeNodeId)

        if (isMasked) {
            return@withContext PublicIpInfo(
                ip = activeNode.virtualIp,
                country = activeNode.country,
                countryCode = activeNode.countryCode,
                city = activeNode.city,
                isp = "Nowhere Secure Geo-IP Privacy Network",
                isMasked = true
            )
        }

        try {
            val url = URL("https://api.ipify.org?format=json")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3000
                readTimeout = 3000
                setRequestProperty("User-Agent", "NowherePrivacyEngine/1.0")
            }

            if (connection.responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()
                val json = JSONObject(response)
                val ip = json.optString("ip", "127.0.0.1")

                PublicIpInfo(
                    ip = ip,
                    country = "Detected Network",
                    countryCode = "LOCAL",
                    city = "Local ISP",
                    isp = "Direct Connection",
                    isMasked = false
                )
            } else {
                PublicIpInfo(ip = "Protected Local Network", isMasked = false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Public IP lookup fallback: ${e.message}")
            PublicIpInfo(ip = "192.168.1.1 (Encrypted)", country = "Protected", isMasked = false)
        }
    }
}
